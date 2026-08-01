package com.complyr.scan

import com.complyr.common.ComplyrProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The scanner tick's queue-draining policy, exercised without a browser: both queues and both crawlers
 * are fakes, so this pins the behaviour the [ScanWorker] itself owns — the paid-before-free ordering
 * (funnel decision #6), the fall-through to the anonymous queue only when the paid one is empty, the
 * per-tick job cap, clean termination when both queues are dry, and that a failing public crawl is
 * recorded as a failure rather than killing the worker.
 */
class ScanWorkerTest {
    private val scanQueue = mockk<ScanQueue>(relaxed = true)
    private val crawler = mockk<ScanCrawler>()
    private val publicScanQueue = mockk<PublicScanQueue>(relaxed = true)
    private val publicCrawler = mockk<PublicScanCrawler>()

    private fun worker(maxJobsPerPoll: Int): ScanWorker =
        ScanWorker(scanQueue, crawler, publicScanQueue, publicCrawler, propertiesWith(maxJobsPerPoll))

    private fun paidClaim() =
        ClaimedScan(
            jobId = UUID.randomUUID(),
            scanId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
            attempt = 1,
            maxAttempts = 3,
        )

    private fun publicClaim() =
        ClaimedPublicScan(
            jobId = UUID.randomUUID(),
            publicScanId = UUID.randomUUID(),
            domain = "acme.example",
            attempt = 1,
            maxAttempts = 1,
        )

    @Test
    fun `drains all paid work before touching the free queue`() {
        val order = mutableListOf<String>()
        val paid = paidClaim()
        val free = publicClaim()
        // Paid queue offers one job then runs dry; the free queue has work waiting the whole time.
        every { scanQueue.claimNext() } returnsMany listOf(paid, null, null)
        every { publicScanQueue.claimNext() } returnsMany listOf(free, null)
        every { crawler.crawl(paid) } answers {
            order.add("paid")
            ScanCrawlResult(pagesCrawled = 3)
        }
        every { publicCrawler.crawl(free) } answers {
            order.add("free")
            ScanCrawlResult(pagesCrawled = 1)
        }

        worker(maxJobsPerPoll = 10).poll()

        assertEquals(listOf("paid", "free"), order, "the paid scan must run before the free one is even claimed")
        verify(exactly = 1) { scanQueue.markSucceeded(paid, 3) }
        verify(exactly = 1) { publicScanQueue.markSucceeded(free) }
    }

    @Test
    fun `falls through to the free queue only when no paid work is waiting`() {
        val free = publicClaim()
        every { scanQueue.claimNext() } returns null
        every { publicScanQueue.claimNext() } returnsMany listOf(free, null)
        every { publicCrawler.crawl(free) } returns ScanCrawlResult(pagesCrawled = 1)

        worker(maxJobsPerPoll = 10).poll()

        verify(exactly = 0) { crawler.crawl(any()) }
        verify(exactly = 1) { publicCrawler.crawl(free) }
        verify(exactly = 1) { publicScanQueue.markSucceeded(free) }
    }

    @Test
    fun `stops after the per-tick cap even when more work remains`() {
        // Both queues are effectively bottomless; the cap is the only thing that ends the tick.
        every { scanQueue.claimNext() } answers { paidClaim() }
        every { crawler.crawl(any()) } returns ScanCrawlResult(pagesCrawled = 1)

        worker(maxJobsPerPoll = 2).poll()

        verify(exactly = 2) { crawler.crawl(any()) }
        verify(exactly = 0) { publicScanQueue.claimNext() }
    }

    @Test
    fun `does no work and terminates when both queues are empty`() {
        every { scanQueue.claimNext() } returns null
        every { publicScanQueue.claimNext() } returns null

        worker(maxJobsPerPoll = 10).poll()

        verify(exactly = 0) { crawler.crawl(any()) }
        verify(exactly = 0) { publicCrawler.crawl(any()) }
    }

    @Test
    fun `a failing public crawl is recorded as a failure with its reason code, not a dead worker`() {
        val free = publicClaim()
        every { scanQueue.claimNext() } returns null
        every { publicScanQueue.claimNext() } returnsMany listOf(free, null)
        every { publicCrawler.crawl(free) } throws
            ScanTargetException(ScanFailureReason.BLOCKED_TARGET, "resolves to 169.254.169.254")

        worker(maxJobsPerPoll = 10).poll()

        // Only the customer-safe reason code reaches the queue; the host-bearing message stays server-side.
        verify(exactly = 1) { publicScanQueue.markFailed(free, ScanFailureReason.BLOCKED_TARGET.code) }
        verify(exactly = 0) { publicScanQueue.markSucceeded(any()) }
    }

    @Test
    fun `an unclassified public crawl error is recorded as an internal failure`() {
        val free = publicClaim()
        every { scanQueue.claimNext() } returns null
        every { publicScanQueue.claimNext() } returnsMany listOf(free, null)
        every { publicCrawler.crawl(free) } throws IllegalStateException("boom")

        worker(maxJobsPerPoll = 10).poll()

        verify(exactly = 1) { publicScanQueue.markFailed(free, ScanFailureReason.INTERNAL.code) }
    }

    private fun propertiesWith(maxJobsPerPoll: Int): ComplyrProperties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.complyr.eu",
            mailFrom = "no-reply@complyr.eu",
            scan = ComplyrProperties.Scan(maxJobsPerPoll = maxJobsPerPoll),
        )
}
