package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.common.CookieKeeperProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan queue against real Postgres. The queue is the backbone of the scanner: it must hand each
 * job to exactly one worker (SKIP LOCKED), keep the `scans` result row in lockstep with the `jobs`
 * delivery row, retry transient failures, and eventually dead-letter a job that never succeeds.
 *
 * A tiny retry budget is pinned so a couple of failures exhaust it without a long test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "cookiekeeper.scan.max-attempts=2",
        "cookiekeeper.scan.retry-backoff=0s",
        "cookiekeeper.scan.visibility-timeout=15m",
    ],
)
class ScanQueueTest {
    @Autowired
    private lateinit var scanQueue: ScanQueue

    @Autowired
    private lateinit var scanRepository: ScanRepository

    @Autowired
    private lateinit var jobRepository: JobRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var properties: CookieKeeperProperties

    // The queue commits its own transactions (the crawl runs between claim and completion with no
    // tx held), so these tests cannot rely on rollback isolation — clear the queue tables between
    // methods instead. Only jobs/scans are wiped: leftover sites/users are harmless (each test
    // inserts fresh ids) and truncating them would cascade into the append-only consent_events
    // table, which a DB guard trigger forbids. CASCADE here only reaches scans' own children.
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE jobs, scans CASCADE")
    }

    @Test
    fun `enqueue creates a queued scan and a pending job referencing it`() {
        val siteId = insertSite()

        val scanId = scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)

        val scan = scanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.QUEUED, scan.status)
        assertEquals(ScanTrigger.SITE_ADDED, scan.trigger)
        assertNull(scan.startedAt, "a queued scan has not started")

        val job = onlyScanJob()
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(0, job.attempts)
        assertEquals(scanId.toString(), job.payload[ScanQueue.PAYLOAD_SCAN_ID])
    }

    @Test
    fun `a future availableAt defers delivery while still showing the scan as queued`() {
        // The scheduled re-scan job jitters availableAt across a window so a nightly batch doesn't hit the
        // single-Chromium worker at once. The customer-visible scan row must still appear immediately.
        val siteId = insertSite()
        val scanId =
            scanQueue.enqueue(siteId, ScanTrigger.SCHEDULED, Instant.now().plus(Duration.ofHours(1)), ScanQueue.PRIORITY_NORMAL)

        assertEquals(ScanStatus.QUEUED, scanRepository.findById(scanId).orElseThrow().status)
        assertNull(scanQueue.claimNext(), "a job is not due until its availableAt passes")
    }

    @Test
    fun `claimNext moves the scan and job to running and marks succeeded records the result`() {
        val siteId = insertSite()
        val scanId = scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)

        val claim = scanQueue.claimNext()
        assertNotNull(claim, "the sole due job must be claimable")
        assertEquals(scanId, claim.scanId)
        assertEquals(siteId, claim.siteId)
        assertEquals(1, claim.attempt)
        assertEquals(ScanStatus.RUNNING, scanRepository.findById(scanId).orElseThrow().status)
        assertEquals(JobStatus.RUNNING, onlyScanJob().status)

        scanQueue.markSucceeded(
            claim,
            ScanCrawlResult(
                pagesCrawled = 7,
                marketingTrackerCount = 4,
                widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 2),
                observedTrackers = listOf("google-analytics.com"),
            ),
        )

        val scan = scanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.DONE, scan.status)
        assertEquals(7, scan.pagesCrawled)
        assertEquals(4, scan.marketingTrackerCount, "the observed marketing-tracker count lands on the scan row")
        assertEquals(true, scan.widgetDetected, "the blocking probe lands on the scan row (BACKLOG #19)")
        assertEquals(true, scan.widgetSiteKeyMatched)
        assertEquals(2, scan.blockedScriptCount)
        assertEquals("google-analytics.com", scan.observedTrackers, "dataset keys are stored, never observed hosts")
        assertNotNull(scan.finishedAt)
        assertEquals(JobStatus.DONE, onlyScanJob().status)
    }

    @Test
    fun `a claimed job is hidden from a second claim until its visibility lock expires`() {
        val siteId = insertSite()
        scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)

        val first = scanQueue.claimNext()
        assertNotNull(first)
        // The job is now running with a 15m visibility lease, so a second worker sees nothing due.
        assertNull(scanQueue.claimNext(), "a running job within its visibility window must not be re-claimed")
    }

    @Test
    fun `a crashed job is redelivered once its visibility lock expires`() {
        val siteId = insertSite()
        scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)
        val first = scanQueue.claimNext()
        assertNotNull(first)

        // Simulate the worker crashing mid-run: force the visibility lock into the past.
        expireVisibilityLock(first.jobId)

        val redelivered = scanQueue.claimNext()
        assertNotNull(redelivered, "an expired-lock job must be redelivered")
        assertEquals(first.scanId, redelivered.scanId)
        assertEquals(2, redelivered.attempt, "redelivery counts as another attempt")
    }

    @Test
    fun `a stale worker cannot complete a job that was already re-claimed`() {
        val siteId = insertSite()
        val scanId = scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)

        // Worker A claims, then overruns its lease and the job is redelivered to worker B.
        val stale = scanQueue.claimNext()
        assertNotNull(stale)
        expireVisibilityLock(stale.jobId)
        val live = scanQueue.claimNext()
        assertNotNull(live)
        assertEquals(2, live.attempt, "the redelivery is a fresh attempt that now owns the job")

        // Worker A finishes late: its completion must be ignored, not clobber B's live claim.
        scanQueue.markSucceeded(stale, ScanCrawlResult(pagesCrawled = 99, marketingTrackerCount = 9))
        val afterStale = scanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.RUNNING, afterStale.status, "a stale success must not mark the scan done")
        assertEquals(JobStatus.RUNNING, onlyScanJob().status)

        // Worker B completes normally and its result is the one that lands.
        scanQueue.markSucceeded(live, ScanCrawlResult(pagesCrawled = 3, marketingTrackerCount = 1))
        val done = scanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.DONE, done.status)
        assertEquals(3, done.pagesCrawled)
    }

    @Test
    fun `markFailed requeues while attempts remain then dead-letters both rows`() {
        val siteId = insertSite()
        val scanId = scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_NORMAL)

        // Attempt 1 fails — with max-attempts=2 and zero backoff it is immediately requeued.
        val attempt1 = scanQueue.claimNext()
        assertNotNull(attempt1)
        scanQueue.markFailed(attempt1, "boom")
        assertEquals(ScanStatus.QUEUED, scanRepository.findById(scanId).orElseThrow().status)
        assertEquals(JobStatus.PENDING, onlyScanJob().status)

        // Attempt 2 fails — attempts now equals max, so the job dead-letters and the scan fails.
        val attempt2 = scanQueue.claimNext()
        assertNotNull(attempt2)
        assertEquals(2, attempt2.attempt)
        scanQueue.markFailed(attempt2, "boom again")

        val scan = scanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.FAILED, scan.status)
        assertEquals("boom again", scan.error)
        assertNotNull(scan.finishedAt)
        val job = onlyScanJob()
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals("boom again", job.lastError)
        assertNull(scanQueue.claimNext(), "a dead-lettered job is never handed out again")
    }

    @Test
    fun `enqueue stamps the priority it is given onto the job`() {
        // Priority is resolved by the caller (see ScanRequestService / ScheduledRescanJob / the site-added
        // listener) and passed in; the queue simply freezes it onto the row so the claim path never
        // re-reads billing state.
        val siteId = insertSite()

        scanQueue.enqueue(siteId, ScanTrigger.SITE_ADDED, Instant.now(), ScanQueue.PRIORITY_HIGH)

        assertEquals(ScanQueue.PRIORITY_HIGH, onlyScanJob().priority, "enqueue persists the caller's priority verbatim")
    }

    @Test
    fun `claimNext serves a higher-priority job ahead of an older normal one`() {
        // The ORDER BY priority DESC, available_at contract: a high-priority job added a moment ago must
        // be crawled before a normal-priority one that has been waiting longer.
        val normalSite = insertSite()
        val prioritySite = insertSite()
        val normalScan =
            scanQueue.enqueue(normalSite, ScanTrigger.SITE_ADDED, Instant.now().minusSeconds(60), ScanQueue.PRIORITY_NORMAL)
        val priorityScan =
            scanQueue.enqueue(prioritySite, ScanTrigger.SITE_ADDED, Instant.now().minusSeconds(30), ScanQueue.PRIORITY_HIGH)

        val first = scanQueue.claimNext()
        assertNotNull(first)
        assertEquals(priorityScan, first.scanId, "the high-priority job wins despite its newer availableAt")

        val second = scanQueue.claimNext()
        assertNotNull(second)
        assertEquals(normalScan, second.scanId, "the older normal job is served once the priority tier drains")
    }

    @Test
    fun `visibility timeout is configured above the max crawl time`() {
        // Guards the ADR-4 invariant: a healthy but slow crawl must never be double-claimed.
        assertTrue(
            properties.scan.visibilityTimeout >= Duration.ofMinutes(10),
            "visibility timeout must exceed the 10min per-job crawl cap (§4.4)",
        )
    }

    private fun insertSite(): UUID {
        val userId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')",
            userId,
            "scan-${UUID.randomUUID()}@example.com",
        )
        val siteId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sites (id, user_id, domain, site_key) VALUES (?, ?, ?, ?)",
            siteId,
            userId,
            "site-${UUID.randomUUID()}.example.com",
            "pk_${UUID.randomUUID()}",
        )
        return siteId
    }

    private fun onlyScanJob(): JobEntity {
        val jobs = jobRepository.findAll().filter { it.type == ScanQueue.JOB_TYPE_SCAN }
        assertEquals(1, jobs.size, "expected exactly one scan job")
        return jobs.first()
    }

    private fun expireVisibilityLock(jobId: UUID) {
        jdbcTemplate.update("UPDATE jobs SET locked_until = now() - interval '1 minute' WHERE id = ?", jobId)
    }
}
