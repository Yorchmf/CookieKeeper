package com.complyr.consent

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Prunes expired dedupe keys from `consent_idempotency`. The table gains one row per consent
 * event on a public, unauthenticated endpoint, so without this it grows unbounded. Keys only
 * need to survive a pending widget retry (the localStorage replay queue drains within days), so
 * anything older than [ComplyrProperties.Consent.idempotencyRetention] is safe to delete — a key
 * whose event is long since recorded can never be replayed to a duplicate.
 *
 * DELETE-pruned rather than DROP PARTITION (the table can't be partitioned on `created_at`
 * without forcing it into the dedupe key), so the window is kept short to bound table bloat —
 * see the V5 migration's bloat note.
 *
 * Batched, one transaction per batch: a single `DELETE ... < cutoff` over a large backlog would be
 * one long transaction pinning the vacuum horizon and bursting dead tuples. Instead the reaper
 * deletes the oldest [ComplyrProperties.Consent.idempotencyPruneBatchSize] rows per transaction
 * and loops until the window is drained. Steady-state churn clears in a single batch; a backlog
 * drains in bounded, vacuum-friendly chunks.
 *
 * Multi-instance safe: `@Scheduled` fires on every replica, so each batch first claims a
 * transaction-scoped Postgres advisory lock and no-ops if another instance already holds it. The
 * lock is re-taken per batch and auto-released when that batch's transaction ends, so a crashed
 * run never strands it and vacuum can run between batches. Whichever replica loses the lock simply
 * stops; the winner drains the backlog.
 */
@Component
class ConsentIdempotencyReaper(
    private val consentIdempotencyRepository: ConsentIdempotencyRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ConsentIdempotencyReaper::class.java)

    // Each batch is its own short transaction (not one @Transactional spanning the whole run), so
    // the advisory lock and delete are scoped per batch and the vacuum horizon isn't held open.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Deletes keys claimed before `now - retention`, in batches of
     * [ComplyrProperties.Consent.idempotencyPruneBatchSize], until the window is drained or the
     * per-run batch cap is hit. Cron-scheduled off-peak; overridable via
     * `complyr.consent.idempotency-prune-cron` (defaulted here so no yml entry is required).
     *
     * Not `@Transactional`: each batch runs in its own transaction via [pruneBatch], so between
     * batches the advisory lock is released and autovacuum can reclaim the freed rows.
     */
    @Scheduled(cron = "\${complyr.consent.idempotency-prune-cron:$DEFAULT_PRUNE_CRON}")
    fun prune() {
        val cutoff = clock.instant().minus(properties.consent.idempotencyRetention)
        val batchSize = properties.consent.idempotencyPruneBatchSize
        var total = 0
        var batches = 0
        var drained = false
        while (batches < MAX_BATCHES_PER_RUN) {
            when (val result = pruneBatch(cutoff, batchSize)) {
                BatchResult.LockContended -> {
                    log.debug("Skipping consent idempotency prune; another instance holds the lock")
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
                "Consent idempotency prune hit the {}-batch cap after removing {} key(s); more may remain for the next run",
                MAX_BATCHES_PER_RUN,
                total,
            )
        }
        if (total > 0) {
            log.info("Pruned {} consent idempotency key(s) claimed before {}", total, cutoff)
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
            if (!consentIdempotencyRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                BatchResult.LockContended
            } else {
                BatchResult.Deleted(consentIdempotencyRepository.deleteBatchClaimedBefore(cutoff, batchSize))
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
         * prune across instances. Keep distinct from any other `pg_advisory*` key the app takes.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 4_827_913_006L

        // Safety cap on batches per run so a bug (or an unexpectedly huge backlog) can't spin the
        // scheduler thread indefinitely; leftovers are picked up by the next scheduled run.
        private const val MAX_BATCHES_PER_RUN = 1_000

        // 03:30 daily (server zone) — off the traffic peak; the window is far wider than the churn.
        private const val DEFAULT_PRUNE_CRON = "0 30 3 * * *"
    }
}
