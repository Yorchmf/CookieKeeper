package com.complyr.scan

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * A claimed scan job handed to a worker: the queue metadata it needs to run the crawl and report
 * back, with no live JPA entities (the worker holds this across a long, transaction-less crawl).
 */
data class ClaimedScan(
    val jobId: UUID,
    val scanId: UUID,
    val siteId: UUID,
    val attempt: Int,
    val maxAttempts: Int,
)

/**
 * Transactional boundary around the scan work queue: enqueue, claim, and terminal transitions.
 * Each method is its own short transaction. Crucially, the long crawl runs BETWEEN [claimNext] and
 * [markSucceeded]/[markFailed] with no transaction open — a claimed job is protected from other
 * workers only by its visibility lock (`locked_until`), not by a held DB lock.
 *
 * The `scans` (result) and `jobs` (queue) rows are always moved together inside one transaction so
 * the two never disagree about a scan's fate.
 */
@Component
class ScanQueue(
    private val scanRepository: ScanRepository,
    private val jobRepository: JobRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ScanQueue::class.java)

    /**
     * Create a queued scan and its pending job in one transaction, returning the new scan id. Called
     * synchronously inside the site-creation transaction (see [ScanEnqueueListener]) so a rolled-back
     * site never leaves an orphan scan, and a committed site always has exactly one queued scan.
     *
     * [availableAt] is when the job first becomes claimable. The interactive callers (site-added,
     * "Re-scan now") pass the current instant; the scheduled re-scan job passes a jittered future one so a
     * nightly batch of due sites doesn't arrive at the single-Chromium worker as one burst. The `scans`
     * row is created `queued` either way, so the dashboard shows the pending scan straight away
     * regardless of when the crawl actually starts.
     *
     * [priority] is the claim-ordering weight ([PRIORITY_HIGH] for a priority-plan site, [PRIORITY_NORMAL]
     * otherwise — see [priorityFor]). It is resolved by the caller, never here: the queue stays free of any
     * billing dependency, and no entitlement lookup runs inside this write transaction (a resolve that hit
     * a transient DB error would otherwise poison the whole enqueue at commit). The scheduled re-scan job
     * reuses the entitlement it already batch-resolved; the interactive callers resolve once from state they
     * already hold. Frozen onto the row at enqueue, so the hot claim path does no billing lookup.
     *
     * Deliberately NOT a Kotlin default parameter: this is a proxied `@Transactional` method, and the
     * synthetic `enqueue$default` bridge Kotlin generates for defaults is static, so it evaluates the
     * default expression against the CGLIB proxy's own (uninitialized) fields — a null-`clock` NPE at the
     * first call, not a compile error.
     */
    @Transactional
    fun enqueue(
        siteId: UUID,
        trigger: ScanTrigger,
        availableAt: Instant,
        priority: Int,
    ): UUID {
        val now = clock.instant()
        val scan =
            scanRepository.save(
                ScanEntity(siteId = siteId, status = ScanStatus.QUEUED, trigger = trigger, createdAt = now, updatedAt = now),
            )
        jobRepository.save(
            JobEntity(
                type = JOB_TYPE_SCAN,
                payload = mapOf(PAYLOAD_SCAN_ID to scan.id.toString()),
                status = JobStatus.PENDING,
                maxAttempts = properties.scan.maxAttempts,
                priority = priority,
                availableAt = availableAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return scan.id
    }

    /**
     * Claim the next due scan job and move both it and its scan to `running`, or return null when
     * the queue is empty. Runs in a single transaction: the `FOR UPDATE SKIP LOCKED` lock from
     * [JobRepository.claimNextId] is held until the running-state save commits, so no other worker
     * can claim the same job in the gap. A job with a missing/dangling scan id is dead-lettered
     * here (it can never succeed) rather than handed out.
     */
    @Transactional
    fun claimNext(): ClaimedScan? {
        val jobId = jobRepository.claimNextId(JOB_TYPE_SCAN) ?: return null
        val job = jobRepository.findById(jobId).orElse(null) ?: return null
        val now = clock.instant()
        // Park available_at on the visibility deadline too, not just locked_until: a running-but-not-yet-
        // expired job then sorts AFTER every currently-due row in the claim scan (ORDER BY available_at),
        // so the LIMIT 1 lookup stops early instead of heap-probing live leases. When the lease lapses the
        // (now past) available_at brings the job back to the front for redelivery.
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

        val scanId = runCatching { UUID.fromString(job.payload[PAYLOAD_SCAN_ID]) }.getOrNull()
        val scan = scanId?.let { scanRepository.findById(it).orElse(null) }
        if (scan == null) {
            log.error("Scan job {} has no resolvable scan (payload={}); dead-lettering", jobId, job.payload)
            jobRepository.save(
                runningJob.copy(
                    status = JobStatus.FAILED,
                    lockedUntil = null,
                    lastError = "unresolvable scan",
                    updatedAt = now,
                ),
            )
            return null
        }

        scanRepository.save(scan.copy(status = ScanStatus.RUNNING, startedAt = scan.startedAt ?: now, updatedAt = now))
        return ClaimedScan(
            jobId = runningJob.id,
            scanId = scan.id,
            siteId = scan.siteId,
            attempt = runningJob.attempts,
            maxAttempts = runningJob.maxAttempts,
        )
    }

    /**
     * Terminal success: mark the job done and the scan done with its page and marketing-tracker counts.
     *
     * Publishes [ScanCompleted] for the scan-complete email. The event is raised inside this transaction
     * but only *delivered* after it commits ([ScanEmailListener]), so a mail failure can never roll the
     * terminal transition back — a rolled-back `markSucceeded` would leave the job leased and let a worker
     * re-crawl a site that was already scanned. Only published when the `scans` row was actually found and
     * moved: a stale claim (rejected above) or a vanished scan owes nobody an email.
     */
    @Transactional
    fun markSucceeded(
        claim: ClaimedScan,
        pagesCrawled: Int,
        marketingTrackerCount: Int,
    ) {
        val job = jobRepository.findById(claim.jobId).orElse(null) ?: return
        if (!ownsClaim(job, claim)) return
        val now = clock.instant()
        jobRepository.save(job.copy(status = JobStatus.DONE, lockedUntil = null, lastError = null, updatedAt = now))
        scanRepository.findById(claim.scanId).ifPresent {
            val done =
                scanRepository.save(
                    it.copy(
                        status = ScanStatus.DONE,
                        finishedAt = now,
                        pagesCrawled = pagesCrawled,
                        marketingTrackerCount = marketingTrackerCount,
                        error = null,
                        updatedAt = now,
                    ),
                )
            eventPublisher.publishEvent(ScanCompleted(scanId = done.id, siteId = done.siteId, trigger = done.trigger))
        }
    }

    /**
     * Terminal-or-retry failure. If the job still has attempts left, it is requeued (`pending`) with
     * a linear backoff on `available_at` and the scan drops back to `queued`; once attempts reach
     * `max_attempts` the job is dead-lettered (`failed`) and the scan is marked `failed` with the
     * (length-bounded) reason.
     */
    @Transactional
    fun markFailed(
        claim: ClaimedScan,
        reason: String,
    ) {
        val job = jobRepository.findById(claim.jobId).orElse(null) ?: return
        if (!ownsClaim(job, claim)) return
        val now = clock.instant()
        val trimmed = reason.take(MAX_ERROR_LENGTH)
        val exhausted = job.attempts >= job.maxAttempts
        if (exhausted) {
            jobRepository.save(job.copy(status = JobStatus.FAILED, lockedUntil = null, lastError = trimmed, updatedAt = now))
            scanRepository.findById(claim.scanId).ifPresent {
                scanRepository.save(it.copy(status = ScanStatus.FAILED, finishedAt = now, error = trimmed, updatedAt = now))
            }
        } else {
            val backoff = properties.scan.retryBackoff.multipliedBy(job.attempts.toLong())
            // last_error keeps the reason for operators; the customer-facing scan.error is cleared while the
            // scan is back in the queue so the dashboard never shows an error on a row that reads "queued".
            jobRepository.save(
                job.copy(
                    status = JobStatus.PENDING,
                    availableAt = now.plus(backoff),
                    lockedUntil = null,
                    lastError = trimmed,
                    updatedAt = now,
                ),
            )
            scanRepository.findById(claim.scanId).ifPresent {
                scanRepository.save(it.copy(status = ScanStatus.QUEUED, error = null, updatedAt = now))
            }
        }
    }

    /**
     * Whether [claim] still owns [job]: the worker's crawl runs with no lock held, so if it overran its
     * visibility lease another worker may have re-claimed the job (bumping `attempts` and re-leasing it).
     * A stale worker reporting completion must not clobber the live claim — the attempt number is the
     * fencing token that tells the two apart.
     */
    private fun ownsClaim(
        job: JobEntity,
        claim: ClaimedScan,
    ): Boolean {
        if (job.status == JobStatus.RUNNING && job.attempts == claim.attempt) return true
        log.warn(
            "Stale claim for job {} (claim attempt {}, row attempt {}, status {}); ignoring late completion",
            claim.jobId,
            claim.attempt,
            job.attempts,
            job.status,
        )
        return false
    }

    companion object {
        const val JOB_TYPE_SCAN = "scan"
        const val PAYLOAD_SCAN_ID = "scanId"

        // Claim-ordering tiers (jobs.priority, higher served first). Two tiers today: priority-plan
        // sites and everyone else. Kept as a gap (0 vs 10) so intermediate tiers can slot in later.
        const val PRIORITY_NORMAL = 0
        const val PRIORITY_HIGH = 10

        /**
         * Map a plan's `priorityScan` entitlement to its claim-ordering tier. The single place the
         * boolean→weight mapping lives, so every caller that resolves the entitlement (the site-added
         * listener, the manual "Re-scan now", the scheduled batch) stamps the same value. The queue owns
         * the tier contract; callers own the billing lookup.
         */
        fun priorityFor(priorityScan: Boolean): Int = if (priorityScan) PRIORITY_HIGH else PRIORITY_NORMAL

        // Scans.error is a display/audit field, not a stack-trace sink — bound it so a pathological
        // exception message can't bloat the row or leak a wall of internal detail into the dashboard.
        private const val MAX_ERROR_LENGTH = 500
    }
}
