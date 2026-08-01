package com.complyr.billing

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Prunes the `stripe_events` inbox once rows age past [ComplyrProperties.Billing.stripeEventRetention].
 * The inbox exists only to dedupe Stripe's at-least-once redelivery and to hold a body long enough to
 * apply (or retry) it; once a row is older than Stripe's redelivery window it can never be redelivered,
 * so keeping it serves nothing. Processed rows are already payload-redacted, but they still carry the
 * event id/type/timestamps, so the reaper bounds how long even those linger (CLAUDE.md #3: retention
 * happens only via scheduled jobs, never ad hoc from request handlers).
 *
 * Unlike `consent_events`, `stripe_events` is a disposable operational inbox, not append-only audit
 * evidence, so DELETE is the right tool. Structure mirrors [com.complyr.scan.PublicScanReaper]: one
 * short transaction per batch, each claiming a distinct transaction-scoped advisory lock so the job is
 * safe to fire on every replica (losers no-op), and looping until the window drains or the per-run cap
 * is hit. (See [com.complyr.common.SchedulingConfig] for why any shared-state `@Scheduled` job must
 * leader-guard itself.)
 */
@Component
class StripeWebhookReaper(
    private val stripeEventRepository: StripeEventRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(StripeWebhookReaper::class.java)

    // Each batch is its own short transaction so the advisory lock is scoped per batch and the vacuum
    // horizon isn't held open across a backlog drain.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Deletes events received before now − retention, in batches of
     * [ComplyrProperties.Billing.stripeEventPruneBatchSize], until the window is drained or the per-run
     * cap is hit. Cron-scheduled off-peak; overridable via `complyr.billing.stripe-event-prune-cron`
     * (defaulted so no yml entry is required, and offset from the other nightly reapers so the sweeps
     * don't collide).
     */
    @Scheduled(cron = "\${complyr.billing.stripe-event-prune-cron:$DEFAULT_PRUNE_CRON}")
    fun prune() {
        val cutoff = clock.instant().minus(properties.billing.stripeEventRetention)
        val batchSize = properties.billing.stripeEventPruneBatchSize
        var total = 0
        var batches = 0
        var drained = false
        while (batches < MAX_BATCHES_PER_RUN) {
            when (val result = pruneBatch(cutoff, batchSize)) {
                BatchResult.LockContended -> {
                    log.debug("Skipping Stripe event prune; another instance holds the lock")
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
                "Stripe event prune hit the {}-batch cap after removing {} event(s); more may remain for the next run",
                MAX_BATCHES_PER_RUN,
                total,
            )
        }
        if (total > 0) {
            log.info("Pruned {} Stripe event(s) received before {}", total, cutoff)
        }
    }

    /**
     * Runs one batch in its own transaction: claims the leader-guard advisory lock, and only if it wins
     * deletes up to [batchSize] rows received before [cutoff]. The lock and delete share the transaction
     * so the lock is held through the delete and released at commit.
     */
    private fun pruneBatch(
        cutoff: Instant,
        batchSize: Int,
    ): BatchResult =
        transactionTemplate.execute {
            if (!stripeEventRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
                BatchResult.LockContended
            } else {
                BatchResult.Deleted(stripeEventRepository.deleteBatchReceivedBefore(cutoff, batchSize))
            }
        } ?: BatchResult.Deleted(0)

    /** Outcome of a single prune batch — a real delete count, or "another instance holds the lock". */
    private sealed interface BatchResult {
        data object LockContended : BatchResult

        data class Deleted(
            val count: Int,
        ) : BatchResult
    }

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) serializing this prune
         * across instances. Kept distinct from every other `pg_advisory*` key the app takes (the
         * consent-idempotency and public-scan reapers).
         */
        internal const val ADVISORY_LOCK_KEY: Long = 8_431_907_662L

        // Safety cap on batches per run so a bug or huge backlog can't spin the scheduler thread
        // indefinitely; leftovers are picked up by the next scheduled run.
        private const val MAX_BATCHES_PER_RUN = 1_000

        // 04:00 daily (server zone) — off the traffic peak and offset from the consent (03:30) and
        // public-scan (03:45) reapers so the nightly sweeps don't contend for IO at the same instant.
        private const val DEFAULT_PRUNE_CRON = "0 0 4 * * *"
    }
}
