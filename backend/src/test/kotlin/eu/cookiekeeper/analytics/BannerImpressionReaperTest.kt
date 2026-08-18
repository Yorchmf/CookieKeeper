package eu.cookiekeeper.analytics

import eu.cookiekeeper.common.CookieKeeperProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Unit coverage for [BannerImpressionReaper]'s orchestration — the batching loop, short-batch "drained"
 * detection, the advisory-lock leader guard, and the per-run batch cap — with a mocked repository so the
 * logic is exercised without Postgres (the SQL itself is covered by [BannerImpressionRepositoryTest], and
 * the shared reaper shape by [eu.cookiekeeper.consent.ConsentIdempotencyReaperTest]). Also pins the cutoff-day
 * arithmetic: retention is a `Duration`, and `LocalDate.minus(Duration)` throws, so a naive subtraction would
 * blow up on every scheduled run — [EXPECTED_CUTOFF] locks the day the prune must compute.
 */
class BannerImpressionReaperTest {
    private val repository = mockk<BannerImpressionRepository>()
    private val properties = mockk<CookieKeeperProperties>()

    // Fixed clock so the cutoff day is deterministic (no midnight-boundary flakiness).
    private val clock = Clock.fixed(Instant.parse("2026-08-18T12:00:00Z"), ZoneOffset.UTC)

    private fun reaper(
        retentionDays: Long = RETENTION_DAYS,
        batchSize: Int = BATCH_SIZE,
    ): BannerImpressionReaper {
        every { properties.impression } returns
            CookieKeeperProperties.Impression(
                retention = Duration.ofDays(retentionDays),
                pruneBatchSize = batchSize,
            )
        return BannerImpressionReaper(repository, properties, clock, immediateTransactionManager())
    }

    // A TransactionTemplate needs a manager; a relaxed mock runs the callback and no-ops commit/rollback,
    // which is all the reaper's per-batch template requires here.
    private fun immediateTransactionManager(): PlatformTransactionManager {
        val txManager = mockk<PlatformTransactionManager>(relaxed = true)
        every { txManager.getTransaction(any()) } returns mockk(relaxed = true)
        return txManager
    }

    @Test
    fun `prune loops until a short batch drains the backlog`() {
        every { repository.tryAcquireAdvisoryXactLock(any()) } returns true
        // Two full batches (== batchSize) then a short one signals the window is drained.
        every { repository.deleteBatchOlderThan(any(), any()) } returnsMany listOf(BATCH_SIZE, BATCH_SIZE, 1)

        reaper().prune()

        // Exactly three batches, each targeting the computed cutoff day — stops on the first short batch.
        verify(exactly = 3) { repository.deleteBatchOlderThan(EXPECTED_CUTOFF, BATCH_SIZE) }
    }

    @Test
    fun `prune runs a single batch when nothing is expired`() {
        every { repository.tryAcquireAdvisoryXactLock(any()) } returns true
        // 0 (< batchSize) is a short batch → drained immediately, no second pass.
        every { repository.deleteBatchOlderThan(any(), any()) } returns 0

        reaper().prune()

        verify(exactly = 1) { repository.deleteBatchOlderThan(EXPECTED_CUTOFF, BATCH_SIZE) }
    }

    @Test
    fun `prune no-ops without deleting while another instance holds the advisory lock`() {
        every { repository.tryAcquireAdvisoryXactLock(any()) } returns false

        reaper().prune()

        verify(exactly = 1) { repository.tryAcquireAdvisoryXactLock(BannerImpressionReaper.ADVISORY_LOCK_KEY) }
        verify(exactly = 0) { repository.deleteBatchOlderThan(any(), any()) }
    }

    @Test
    fun `prune stops at the per-run batch cap when the backlog never drains`() {
        every { repository.tryAcquireAdvisoryXactLock(any()) } returns true
        // Every batch comes back full, so the window never signals drained; the reaper must stop at the cap
        // rather than spin the scheduler thread forever, leaving the rest for the next run.
        every { repository.deleteBatchOlderThan(any(), any()) } returns BATCH_SIZE

        reaper().prune()

        verify(exactly = MAX_BATCHES_PER_RUN) { repository.deleteBatchOlderThan(EXPECTED_CUTOFF, BATCH_SIZE) }
    }

    private companion object {
        const val RETENTION_DAYS = 210L
        const val BATCH_SIZE = 2

        // 2026-08-18 (fixed clock, UTC) minus the 210-day retention: the exact cutoff the prune must compute.
        // Guards the Duration→days conversion — LocalDate.minus(Duration) would throw at runtime.
        val EXPECTED_CUTOFF: LocalDate = LocalDate.of(2026, 8, 18).minusDays(RETENTION_DAYS)

        // Mirrors the reaper's private MAX_BATCHES_PER_RUN safety cap.
        const val MAX_BATCHES_PER_RUN = 1_000
    }
}
