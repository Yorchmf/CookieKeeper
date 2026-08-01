package com.complyr.scan

import com.complyr.auth.OpaqueTokens
import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * A claimed anonymous-scan job handed to a worker: the queue metadata plus the bare [domain] to
 * crawl (no owning site, no verified-domain gate), with no live JPA entities — the worker holds this
 * across a long, transaction-less crawl (mirrors [ClaimedScan]).
 */
data class ClaimedPublicScan(
    val jobId: UUID,
    val publicScanId: UUID,
    val domain: String,
    val attempt: Int,
    val maxAttempts: Int,
)

/**
 * Transactional boundary around the anonymous free-scan queue — the `public_scans` twin of
 * [ScanQueue]. It reuses the same generic `jobs` table (ADR-4) with a distinct [JOB_TYPE_PUBLIC_SCAN]
 * so one worker pool drains both, and the same `FOR UPDATE SKIP LOCKED` claim + attempt-fencing
 * discipline; only the result table (`public_scans`, no owning site / no `startedAt`/`pagesCrawled`
 * columns) and the fail-fast retry policy differ.
 *
 * SSRF posture (docs ADR-12): there is deliberately NO ownership/verified gate here — the domain is
 * visitor-supplied. [ScanTargetValidator] (run inside the engine) plus the scanner's network
 * isolation are the load-bearing defense; enqueue only syntactically normalizes the domain.
 */
@Component
class PublicScanQueue(
    private val publicScanRepository: PublicScanRepository,
    private val publicScanCookieRepository: PublicScanCookieRepository,
    private val cookieWriter: PublicScanCookieWriter,
    private val jobRepository: JobRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PublicScanQueue::class.java)

    /**
     * Create a queued anonymous scan and its pending job in one transaction, returning the read token.
     * The caller has already normalized [domain] and decided no fresh cached result exists.
     */
    @Transactional
    fun enqueue(
        domain: String,
        ipHash: String?,
    ): String {
        val now = clock.instant()
        val scan =
            publicScanRepository.save(
                PublicScanEntity(
                    domain = domain,
                    status = ScanStatus.QUEUED,
                    publicToken = OpaqueTokens.generate(),
                    ipHash = ipHash,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = now.plus(RESULT_TTL),
                ),
            )
        jobRepository.save(
            JobEntity(
                type = JOB_TYPE_PUBLIC_SCAN,
                payload = mapOf(PAYLOAD_PUBLIC_SCAN_ID to scan.id.toString()),
                status = JobStatus.PENDING,
                // Anonymous scans fail fast: a bad/hostile domain must not burn crawl compute on
                // retries, and a fast definitive verdict converts better than a delayed one (§ funnel).
                maxAttempts = PUBLIC_SCAN_MAX_ATTEMPTS,
                availableAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return scan.publicToken
    }

    /**
     * Materialize a per-visitor result from a still-fresh cached crawl of the same domain, returning the
     * new read token. Instead of sharing one row+token across every visitor of a domain (which would
     * collide on the Slice-E `email` lead slot and could disclose one visitor's email to another), each
     * request gets its OWN `done` row — its own token, its own null `email` slot, its own [ipHash] — and
     * we copy the cached crawl's (already capped/classified) cookies onto it. This reuses the expensive
     * crawl artifact without re-crawling, but never shares identity between visitors.
     *
     * No job row is created: the result is `done` on arrival, so there is nothing for a worker to run.
     */
    @Transactional
    fun reuseCachedResult(
        cached: PublicScanEntity,
        ipHash: String?,
    ): String {
        val now = clock.instant()
        val copy =
            publicScanRepository.save(
                PublicScanEntity(
                    domain = cached.domain,
                    status = ScanStatus.DONE,
                    publicToken = OpaqueTokens.generate(),
                    ipHash = ipHash,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = now.plus(RESULT_TTL),
                ),
            )
        // Re-key each cached finding onto the new row (fresh PK + FK); the cookie set is bounded and
        // truncated at crawl time (ScanCookieMapper caps), so this copy is cheap.
        val copiedCookies =
            publicScanCookieRepository
                .findByPublicScanId(cached.id)
                .map { it.copy(id = UUID.randomUUID(), publicScanId = copy.id) }
        cookieWriter.replace(copy.id, copiedCookies)
        return copy.publicToken
    }

    /**
     * Claim the next due anonymous-scan job and move both it and its scan to `running`, or null when
     * the public queue is empty. Same single-transaction / lease discipline as [ScanQueue.claimNext];
     * a job with a missing/dangling scan id is dead-lettered here rather than handed out.
     */
    @Transactional
    fun claimNext(): ClaimedPublicScan? {
        val jobId = jobRepository.claimNextId(JOB_TYPE_PUBLIC_SCAN) ?: return null
        val job = jobRepository.findById(jobId).orElse(null) ?: return null
        val now = clock.instant()
        // Same lease semantics as the paid path — the crawl runs on the same worker under the same
        // visibility timeout; only the retry policy (fail-fast) differs.
        val lease = now.plus(properties.scan.visibilityTimeout)
        val runningJob =
            jobRepository.save(
                job.copy(
                    status = JobStatus.RUNNING,
                    attempts = job.attempts + 1,
                    availableAt = lease,
                    lockedUntil = lease,
                    updatedAt = now,
                ),
            )

        val scanId = runCatching { UUID.fromString(job.payload[PAYLOAD_PUBLIC_SCAN_ID]) }.getOrNull()
        val scan = scanId?.let { publicScanRepository.findById(it).orElse(null) }
        if (scan == null) {
            log.error("Public-scan job {} has no resolvable scan (payload={}); dead-lettering", jobId, job.payload)
            jobRepository.save(
                runningJob.copy(status = JobStatus.FAILED, lockedUntil = null, lastError = "unresolvable scan", updatedAt = now),
            )
            return null
        }

        publicScanRepository.save(scan.copy(status = ScanStatus.RUNNING, updatedAt = now))
        return ClaimedPublicScan(
            jobId = runningJob.id,
            publicScanId = scan.id,
            domain = scan.domain,
            attempt = runningJob.attempts,
            maxAttempts = runningJob.maxAttempts,
        )
    }

    /** Terminal success: mark the job done and the scan done. */
    @Transactional
    fun markSucceeded(claim: ClaimedPublicScan) {
        val job = jobRepository.findById(claim.jobId).orElse(null) ?: return
        if (!ownsClaim(job, claim)) return
        val now = clock.instant()
        jobRepository.save(job.copy(status = JobStatus.DONE, lockedUntil = null, lastError = null, updatedAt = now))
        publicScanRepository.findById(claim.publicScanId).ifPresent {
            publicScanRepository.save(it.copy(status = ScanStatus.DONE, error = null, updatedAt = now))
        }
    }

    /**
     * Terminal-or-retry failure. With [PUBLIC_SCAN_MAX_ATTEMPTS] = 1 this is effectively terminal:
     * the job is dead-lettered and the scan marked `failed` with the (length-bounded) reason code.
     * The retry branch is kept structurally identical to [ScanQueue.markFailed] so the policy is a
     * one-constant change, not a behavioral fork.
     */
    @Transactional
    fun markFailed(
        claim: ClaimedPublicScan,
        reason: String,
    ) {
        val job = jobRepository.findById(claim.jobId).orElse(null) ?: return
        if (!ownsClaim(job, claim)) return
        val now = clock.instant()
        val trimmed = reason.take(MAX_ERROR_LENGTH)
        val exhausted = job.attempts >= job.maxAttempts
        if (exhausted) {
            jobRepository.save(job.copy(status = JobStatus.FAILED, lockedUntil = null, lastError = trimmed, updatedAt = now))
            publicScanRepository.findById(claim.publicScanId).ifPresent {
                publicScanRepository.save(it.copy(status = ScanStatus.FAILED, error = trimmed, updatedAt = now))
            }
        } else {
            val backoff = properties.scan.retryBackoff.multipliedBy(job.attempts.toLong())
            jobRepository.save(
                job.copy(
                    status = JobStatus.PENDING,
                    availableAt = now.plus(backoff),
                    lockedUntil = null,
                    lastError = trimmed,
                    updatedAt = now,
                ),
            )
            publicScanRepository.findById(claim.publicScanId).ifPresent {
                publicScanRepository.save(it.copy(status = ScanStatus.QUEUED, error = null, updatedAt = now))
            }
        }
    }

    /**
     * Whether [claim] still owns [job] — the attempt number is the fencing token that tells a live
     * claim apart from a stale worker reporting late after its visibility lease lapsed and the job was
     * re-claimed (identical to [ScanQueue.ownsClaim]).
     */
    private fun ownsClaim(
        job: JobEntity,
        claim: ClaimedPublicScan,
    ): Boolean {
        if (job.status == JobStatus.RUNNING && job.attempts == claim.attempt) return true
        log.warn(
            "Stale claim for public-scan job {} (claim attempt {}, row attempt {}, status {}); ignoring late completion",
            claim.jobId,
            claim.attempt,
            job.attempts,
            job.status,
        )
        return false
    }

    companion object {
        const val JOB_TYPE_PUBLIC_SCAN = "public_scan"
        const val PAYLOAD_PUBLIC_SCAN_ID = "publicScanId"

        // Anonymous scans don't *retry on failure* (fail-fast; bounds abuse compute and gives a fast
        // verdict). This is not a hard once-only guarantee: like the paid path, a lease that lapses
        // mid-crawl (worker stall/GC pause past the visibility timeout) can still redeliver the job once
        // and produce a duplicate crawl — the attempt-fencing token only prevents a stale completion
        // from clobbering the live one, not the concurrent second crawl itself.
        // Lease + backoff are shared with the paid path via properties.scan; only this differs.
        private const val PUBLIC_SCAN_MAX_ATTEMPTS = 1

        // Anonymous results are short-lived (§9: 7-day TTL); the reaper (slice G) purges past this.
        private val RESULT_TTL: Duration = Duration.ofDays(7)

        private const val MAX_ERROR_LENGTH = 500
    }
}
