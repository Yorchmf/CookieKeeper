package eu.cookiekeeper.scan

import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Purges anonymous free scans past their TTL horizon from `public_scans` (cookies cascade via the
 * FK's `ON DELETE CASCADE`). Every public scan writes an `expires_at` of created + 7 days
 * ([PublicScanQueue.RESULT_TTL]); this reaper deletes any row whose horizon is in the past. Without
 * it the acquisition-funnel table — fed by an unauthenticated endpoint — grows unbounded, and
 * GDPR-wise we would retain visitor domains + lead emails long past the stated window (CLAUDE.md #3:
 * retention/erasure happens only via scheduled jobs, never ad hoc from request handlers).
 *
 * Unlike `consent_events`, `public_scans` is replaceable acquisition data, not append-only audit
 * evidence, so DELETE is the right tool here. The TTL is applied at write time, so the reaper needs
 * no retention window of its own — it simply prunes `expires_at < now`.
 *
 * Batched, one transaction per batch: a single `DELETE ... < now` over a backlog would be one long
 * transaction pinning the vacuum horizon and bursting dead tuples (amplified by the cookie cascade).
 * Instead it deletes the oldest-expiring [CookieKeeperProperties.Scan.publicScanPruneBatchSize] rows per
 * transaction and loops until the window is drained. Steady-state churn clears in a single batch; a
 * backlog drains in bounded, vacuum-friendly chunks.
 *
 * Multi-instance safe: `@Scheduled` fires on every replica, so each batch first claims a
 * transaction-scoped Postgres advisory lock and no-ops if another instance already holds it. The
 * lock is re-taken per batch and auto-released when that batch's transaction ends, so a crashed run
 * never strands it and vacuum can run between batches. Whichever replica loses the lock simply
 * stops; the winner drains the backlog. (See [eu.cookiekeeper.common.SchedulingConfig] for why any new
 * shared-state `@Scheduled` job must leader-guard itself.)
 */
@Component
class PublicScanReaper(
    private val publicScanRepository: PublicScanRepository,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(PublicScanReaper::class.java)

    // Each batch is its own short transaction (not one @Transactional spanning the whole run), so the
    // advisory lock and cascading delete are scoped per batch and the vacuum horizon isn't held open.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Deletes scans whose `expires_at` is before now, in batches of
     * [CookieKeeperProperties.Scan.publicScanPruneBatchSize], until the window is drained or the per-run
     * batch cap is hit. Cron-scheduled off-peak; overridable via `cookiekeeper.scan.public-scan-prune-cron`
     * (defaulted here so no yml entry is required, and offset from the consent reaper so the two
     * nightly sweeps don't collide).
     *
     * Not `@Transactional`: each batch runs in its own transaction via [pruneBatch], so between
     * batches the advisory lock is released and autovacuum can reclaim the freed rows.
     */
    @Scheduled(cron = "\${complyr.scan.public-scan-prune-cron:$DEFAULT_PRUNE_CRON}")
    fun prune() {
        val cutoff = clock.instant()
        val batchSize = properties.scan.publicScanPruneBatchSize
        var total = 0
        var batches = 0
        var drained = false
        while (batches < MAX_BATCHES_PER_RUN) {
            when (val result = pruneBatch(cutoff, batchSize)) {
                BatchResult.LockContended -> {
                    log.debug("Skipping public scan prune; another instance holds the lock")
                    return
                }
                is BatchResult.Deleted -> {
                    total += result.count
                    batches++
                    // A short (or empty) batch means the window is drained; a full one means more remain.
                    if (result.count < batchSize) {
                        drained = true
                        break
                    }
                }
            }
        }
        if (!drained) {
            log.warn(
                "Public scan prune hit the {}-batch cap after removing {} scan(s); more may remain for the next run",
                MAX_BATCHES_PER_RUN,
                total,
            )
        }
        if (total > 0) {
            log.info("Pruned {} expired public scan(s) with expires_at before {}", total, cutoff)
        }
    }

    /**
     * Runs one batch in its own transaction: claims the leader-guard advisory lock, and only if it
     * wins deletes up to [batchSize] expired rows. Returns [BatchResult.Deleted] with the rows
     * removed, or [BatchResult.LockContended] when another instance holds the lock. The lock and
     * delete share this transaction so the lock is held through the delete and released at commit.
     */
    private fun pruneBatch(
        cutoff: Instant,
        batchSize: Int,
    ): BatchResult =
        transactionTemplate.execute {
            if (!publicScanRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                BatchResult.LockContended
            } else {
                BatchResult.Deleted(publicScanRepository.deleteBatchExpiredBefore(cutoff, batchSize))
            }
        }

    /** Outcome of a single prune batch — a real delete count, or "another instance holds the lock". */
    private sealed interface BatchResult {
        data object LockContended : BatchResult

        data class Deleted(
            val count: Int,
        ) : BatchResult
    }

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes the
         * prune across instances. Kept distinct from [eu.cookiekeeper.consent.ConsentIdempotencyReaper]'s
         * key and any other `pg_advisory*` key the app takes.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 7_213_884_559L

        // Safety cap on batches per run so a bug (or an unexpectedly huge backlog) can't spin the
        // scheduler thread indefinitely; leftovers are picked up by the next scheduled run.
        private const val MAX_BATCHES_PER_RUN = 1_000

        // 03:45 daily (server zone) — off the traffic peak and 15 min after the consent reaper (03:30)
        // so the two nightly sweeps don't contend for IO at the same instant.
        private const val DEFAULT_PRUNE_CRON = "0 45 3 * * *"
    }
}
