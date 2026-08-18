package com.complyr.analytics

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Prunes old rows from `banner_impressions` (Track 4 Slice D). The table gains one row per
 * (site, day) the banner is shown, and impressions are read over the dashboard's analytics windows
 * — including the period-over-period *prior* window, which reaches back up to twice the widest
 * preset (a 90-day view compares against days 90–180 back). [ComplyrProperties.Impression.retention]
 * is sized to exceed that doubled reach, so counters older than it can never affect a shown figure
 * or a delta and are safe to delete. Bounds table growth on a public, unauthenticated ingest
 * endpoint.
 *
 * DELETE-pruned freely because this is a disposable aggregate, not audit evidence (unlike the
 * append-only, partitioned consent log — see the V26 migration): a pruned counter leaves no
 * gap in any record we must keep, and it stores no personal data, so there is nothing to erase
 * or preserve. Mirrors [com.complyr.consent.ConsentIdempotencyReaper] in every operational
 * respect (batched one-transaction-per-batch, advisory-lock leader guard, per-run batch cap).
 *
 * Batched, one transaction per batch: a single `DELETE ... < cutoff` over a large backlog would
 * be one long transaction pinning the vacuum horizon and bursting dead tuples. Instead the reaper
 * deletes the oldest [ComplyrProperties.Impression.pruneBatchSize] rows per transaction and loops
 * until the window is drained. Steady-state churn clears in a single batch; a backlog drains in
 * bounded, vacuum-friendly chunks.
 *
 * Multi-instance safe: `@Scheduled` fires on every replica, so each batch first claims a
 * transaction-scoped Postgres advisory lock and no-ops if another instance already holds it. The
 * lock is re-taken per batch and auto-released when that batch's transaction ends, so a crashed
 * run never strands it and vacuum can run between batches. Whichever replica loses the lock simply
 * stops; the winner drains the backlog.
 */
@Component
class BannerImpressionReaper(
    private val bannerImpressionRepository: BannerImpressionRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(BannerImpressionReaper::class.java)

    // Each batch is its own short transaction (not one @Transactional spanning the whole run), so
    // the advisory lock and delete are scoped per batch and the vacuum horizon isn't held open.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Deletes counters for days strictly before `today(UTC) - retention`, in batches of
     * [ComplyrProperties.Impression.pruneBatchSize], until the window is drained or the per-run
     * batch cap is hit. Cron-scheduled off-peak; overridable via
     * `complyr.impression.prune-cron` (defaulted here so no yml entry is required).
     *
     * The cutoff is a whole UTC calendar day matching the counter's own `day` grain and the day
     * the ingestion service stamps ([com.complyr.consent.ImpressionService]).
     *
     * Not `@Transactional`: each batch runs in its own transaction via [pruneBatch], so between
     * batches the advisory lock is released and autovacuum can reclaim the freed rows.
     */
    @Scheduled(cron = "\${complyr.impression.prune-cron:$DEFAULT_PRUNE_CRON}")
    fun prune() {
        // retention is a Duration; LocalDate.minus(Duration) throws (a date can't subtract a time-based
        // unit), so convert to whole days. The counter grain and cutoff are day-resolution, so this is exact.
        val cutoffDay =
            LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(properties.impression.retention.toDays())
        val batchSize = properties.impression.pruneBatchSize
        var total = 0
        var batches = 0
        var drained = false
        while (batches < MAX_BATCHES_PER_RUN) {
            when (val result = pruneBatch(cutoffDay, batchSize)) {
                BatchResult.LockContended -> {
                    log.debug("Skipping banner impression prune; another instance holds the lock")
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
                "Banner impression prune hit the {}-batch cap after removing {} counter(s); more may remain for the next run",
                MAX_BATCHES_PER_RUN,
                total,
            )
        }
        if (total > 0) {
            log.info("Pruned {} banner impression counter(s) for days before {}", total, cutoffDay)
        }
    }

    /**
     * Runs one batch in its own transaction: claims the leader-guard advisory lock, and only if it
     * wins deletes up to [batchSize] counters for days before [cutoffDay]. Returns
     * [BatchResult.Deleted] with the rows removed, or [BatchResult.LockContended] when another
     * instance holds the lock. The lock and delete share this transaction so the lock is held
     * through the delete and released at commit.
     */
    private fun pruneBatch(
        cutoffDay: LocalDate,
        batchSize: Int,
    ): BatchResult =
        transactionTemplate.execute {
            if (!bannerImpressionRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                BatchResult.LockContended
            } else {
                BatchResult.Deleted(bannerImpressionRepository.deleteBatchOlderThan(cutoffDay, batchSize))
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
         * prune across instances. Distinct from every other `pg_advisory*` key the app takes.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 9_147_026_531L

        // Safety cap on batches per run so a bug (or an unexpectedly huge backlog) can't spin the
        // scheduler thread indefinitely; leftovers are picked up by the next scheduled run.
        private const val MAX_BATCHES_PER_RUN = 1_000

        // 03:45 daily (server zone) — off the traffic peak, and staggered off the consent
        // idempotency prune (03:30) so the two nightly reapers don't contend for the DB at once.
        private const val DEFAULT_PRUNE_CRON = "0 45 3 * * *"
    }
}
