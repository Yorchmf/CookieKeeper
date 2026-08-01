package com.complyr.scan

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The anonymous-scan queue against real Postgres — the `public_scans` twin of [ScanQueueTest]. It shares
 * the generic `jobs` table (distinct type) and the same SKIP-LOCKED lease/fencing discipline, so this
 * focuses on what differs: no owning site, a 7-day result TTL stamped at enqueue, and the fail-fast
 * (max-attempts = 1) policy that dead-letters on the first failure instead of retrying.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "complyr.scan.retry-backoff=0s",
        "complyr.scan.visibility-timeout=15m",
    ],
)
class PublicScanQueueTest {
    @Autowired
    private lateinit var publicScanQueue: PublicScanQueue

    @Autowired
    private lateinit var publicScanRepository: PublicScanRepository

    @Autowired
    private lateinit var jobRepository: JobRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // The queue commits its own transactions (the crawl runs between claim and completion with no tx
    // held), so these tests clear the queue tables between methods rather than rely on rollback.
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE jobs, public_scans CASCADE")
    }

    @Test
    fun `enqueue creates a queued scan with a 7-day TTL and a pending fail-fast job`() {
        val token = publicScanQueue.enqueue(domain = "acme.example", ipHash = "hash_1")

        val scan = assertNotNull(publicScanRepository.findByPublicToken(token))
        assertEquals("acme.example", scan.domain)
        assertEquals("hash_1", scan.ipHash)
        assertEquals(ScanStatus.QUEUED, scan.status)
        // TTL is stamped at enqueue (7d) — the reaper (slice G) purges past it.
        assertTrue(scan.expiresAt.isAfter(scan.createdAt), "a retention horizon is set ahead of creation")

        val job = onlyPublicJob()
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(0, job.attempts)
        assertEquals(1, job.maxAttempts, "anonymous scans are fail-fast: a single attempt, no retries")
        assertEquals(scan.id.toString(), job.payload[PublicScanQueue.PAYLOAD_PUBLIC_SCAN_ID])
    }

    @Test
    fun `claimNext moves the scan and job to running and marks succeeded records done`() {
        val token = publicScanQueue.enqueue(domain = "acme.example", ipHash = null)
        val scanId = assertNotNull(publicScanRepository.findByPublicToken(token)).id

        val claim = assertNotNull(publicScanQueue.claimNext(), "the sole due job must be claimable")
        assertEquals(scanId, claim.publicScanId)
        assertEquals("acme.example", claim.domain)
        assertEquals(1, claim.attempt)
        assertEquals(ScanStatus.RUNNING, publicScanRepository.findById(scanId).orElseThrow().status)
        assertEquals(JobStatus.RUNNING, onlyPublicJob().status)

        publicScanQueue.markSucceeded(claim)

        assertEquals(ScanStatus.DONE, publicScanRepository.findById(scanId).orElseThrow().status)
        assertEquals(JobStatus.DONE, onlyPublicJob().status)
    }

    @Test
    fun `a claimed job is hidden from a second claim until its visibility lock expires`() {
        publicScanQueue.enqueue(domain = "acme.example", ipHash = null)

        assertNotNull(publicScanQueue.claimNext())
        assertNull(publicScanQueue.claimNext(), "a running job within its visibility window must not be re-claimed")
    }

    @Test
    fun `markFailed dead-letters both rows on the first failure (no retry)`() {
        val token = publicScanQueue.enqueue(domain = "acme.example", ipHash = null)
        val scanId = assertNotNull(publicScanRepository.findByPublicToken(token)).id

        val claim = assertNotNull(publicScanQueue.claimNext())
        assertEquals(1, claim.attempt)
        publicScanQueue.markFailed(claim, "BLOCKED_TARGET")

        // max-attempts = 1, so attempt 1 exhausts the budget: no requeue, straight to dead-letter.
        val scan = publicScanRepository.findById(scanId).orElseThrow()
        assertEquals(ScanStatus.FAILED, scan.status)
        assertEquals("BLOCKED_TARGET", scan.error)
        val job = onlyPublicJob()
        assertEquals(JobStatus.FAILED, job.status)
        assertEquals("BLOCKED_TARGET", job.lastError)
        assertNull(publicScanQueue.claimNext(), "a dead-lettered job is never handed out again")
    }

    @Test
    fun `a stale worker cannot complete a job that was already re-claimed`() {
        val token = publicScanQueue.enqueue(domain = "acme.example", ipHash = null)
        val scanId = assertNotNull(publicScanRepository.findByPublicToken(token)).id

        val stale = assertNotNull(publicScanQueue.claimNext())
        expireVisibilityLock(stale.jobId)
        val live = assertNotNull(publicScanQueue.claimNext())
        assertEquals(2, live.attempt, "the redelivery is a fresh attempt that now owns the job")

        // The stale worker finishes late: its completion must be ignored, not clobber the live claim.
        publicScanQueue.markSucceeded(stale)
        assertEquals(
            ScanStatus.RUNNING,
            publicScanRepository.findById(scanId).orElseThrow().status,
            "a stale success must not mark the scan done",
        )

        publicScanQueue.markSucceeded(live)
        assertEquals(ScanStatus.DONE, publicScanRepository.findById(scanId).orElseThrow().status)
    }

    private fun onlyPublicJob(): JobEntity {
        val jobs = jobRepository.findAll().filter { it.type == PublicScanQueue.JOB_TYPE_PUBLIC_SCAN }
        assertEquals(1, jobs.size, "expected exactly one public-scan job")
        return jobs.first()
    }

    private fun expireVisibilityLock(jobId: UUID) {
        jdbcTemplate.update("UPDATE jobs SET locked_until = now() - interval '1 minute' WHERE id = ?", jobId)
    }
}
