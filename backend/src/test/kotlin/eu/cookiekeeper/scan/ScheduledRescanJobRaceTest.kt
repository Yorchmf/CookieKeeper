package eu.cookiekeeper.scan

import eu.cookiekeeper.billing.AccountEntitlement
import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.site.RescanCandidate
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Whitebox test for the race between [ScheduledRescanJob]'s per-site lock and
 * [eu.cookiekeeper.account.AccountDeletionService]'s per-user erasure lock, which never intersect: a
 * site [ScheduledRescanJob.selectDueSites] read as due can be archived by a concurrent erasure before
 * this job's own per-site enqueue transaction runs. [ScheduledRescanJobTest] runs the whole batch against
 * real Postgres in one thread with no seam to interleave a concurrent write mid-run, so this test mocks
 * every collaborator to force exactly that ordering instead.
 */
class ScheduledRescanJobRaceTest {
    private class NoopTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }

    private val now = Instant.parse("2026-08-01T04:20:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val scanRepository = mockk<ScanRepository>(relaxed = true)
    private val entitlementService = mockk<EntitlementService>()
    private val scanQueue = mockk<ScanQueue>()

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "test-only-jwt-secret-0123456789-abcdefghij",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            scan = CookieKeeperProperties.Scan(rescanJitterWindow = Duration.ZERO),
            appBaseUrl = "https://app.cookiekeeper.test",
            cdnBaseUrl = "https://cdn.cookiekeeper.test",
            mailFrom = "support@cookiekeeper.test",
        )

    private val job =
        ScheduledRescanJob(
            siteRepository,
            scanRepository,
            entitlementService,
            scanQueue,
            properties,
            Clock.fixed(now, ZoneOffset.UTC),
            NoopTransactionManager(),
        )

    private val userId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()

    @Test
    fun `a site archived by a concurrent erasure after selection is skipped, not enqueued`() {
        val candidate = mockk<RescanCandidate>()
        every { candidate.siteId } returns siteId
        every { candidate.userId } returns userId
        every { candidate.lastScanAt } returns now.minus(Duration.ofDays(40))

        every { scanRepository.tryAcquireAdvisoryXactLock(any()) } returns true
        every { siteRepository.findRescanCandidates(any(), any()) } returns listOf(candidate)
        every { entitlementService.resolveAll(listOf(userId)) } returns
            mapOf(userId to AccountEntitlement.Subscribed(Plan.PRO))
        // The status re-check under the per-site lock — taken AFTER selection — sees the site an erasure
        // already archived in the meantime (AccountDeletionService's per-USER lock never intersects this
        // one). It reads via the status projection, not a second findById, so it genuinely re-queries
        // instead of being served a stale managed instance from the persistence context (see
        // SiteRepository.findStatusById).
        every { siteRepository.findStatusById(siteId) } returns SiteStatus.ARCHIVED

        job.enqueueDueRescans()

        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }
}
