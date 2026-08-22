package eu.cookiekeeper.billing

import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Prunes the `stripe_events` inbox once rows age past [CookieKeeperProperties.Billing.stripeEventRetention],
 * and — ahead of that — redacts the raw payload of any row that is STILL UNPROCESSED once it ages past
 * the much shorter [CookieKeeperProperties.Billing.stripeEventPoisonPayloadRetention]. The inbox exists
 * only to dedupe Stripe's at-least-once redelivery and to hold a body long enough to apply (or retry)
 * it; once a row is older than Stripe's redelivery window it can never be redelivered, so keeping it
 * serves nothing. Processed rows are already payload-redacted on success
 * ([StripeEventRepository.markProcessedAndRedact]), but that never fires for a POISON event — one that
 * keeps failing to apply (a bug, an unattributable subscription) — so without the earlier redaction pass
 * its body, which for checkout/subscription events includes customer PII, would otherwise sit for the
 * full retention window instead of a bounded few days (CLAUDE.md #4: no PII at rest beyond what's
 * needed). Retention itself bounds how long even a redacted row's id/type/timestamps linger (CLAUDE.md
 * #3: retention happens only via scheduled jobs, never ad hoc from request handlers).
 *
 * Unlike `consent_events`, `stripe_events` is a disposable operational inbox, not append-only audit
 * evidence, so DELETE (and, for the poison case, an early in-place redaction) is the right tool.
 * Structure mirrors [eu.cookiekeeper.scan.PublicScanReaper]: one short transaction per batch, each
 * claiming a distinct transaction-scoped advisory lock so the job is safe to fire on every replica
 * (losers no-op), and looping until the window drains or the per-run cap is hit. (See
 * [eu.cookiekeeper.common.SchedulingConfig] for why any shared-state `@Scheduled` job must leader-guard
 * itself.)
 */
@Component
class StripeWebhookReaper(
    private val stripeEventRepository: StripeEventRepository,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(StripeWebhookReaper::class.java)

    // Each batch is its own short transaction so the advisory lock is scoped per batch and the vacuum
    // horizon isn't held open across a backlog drain.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Runs the poison-payload redaction pass, then the full-row prune, in that order — a row worth
     * deleting is also worth redacting first if somehow neither has run yet, and the reverse ordering
     * would let the delete's cutoff race a row past the (always-shorter, per the config validation)
     * redaction cutoff. Cron-scheduled off-peak; overridable via
     * `cookiekeeper.billing.stripe-event-prune-cron` (defaulted so no yml entry is required, and offset
     * from the other nightly reapers so the sweeps don't collide).
     */
    @Scheduled(cron = "\${cookiekeeper.billing.stripe-event-prune-cron:$DEFAULT_PRUNE_CRON}")
    fun prune() {
        redactStalePoisonedPayloads()
        pruneExpiredEvents()
    }

    /**
     * Nulls the payload of events received before now − [poisonPayloadRetention] that are still
     * unprocessed, in batches of [batchSize], until the window is drained or the per-run cap is hit.
     * A successfully-applied event never reaches this (it was already redacted on process), so every row
     * this touches is, by definition, one that has failed to apply for longer than Stripe's own retry
     * window ever needs.
     */
    private fun redactStalePoisonedPayloads() {
        val cutoff = clock.instant().minus(properties.billing.stripeEventPoisonPayloadRetention)
        val batchSize = properties.billing.stripeEventPruneBatchSize
        val total =
            runBatches(ADVISORY_LOCK_KEY_REDACT, "Stripe poison-payload redaction") { size ->
                stripeEventRepository.redactStalePoisonedPayloads(cutoff, size)
            }
        if (total > 0) {
            log.warn(
                "Redacted {} still-unprocessed Stripe event payload(s) received before {} " +
                    "(poison event(s): never applied within the retry window)",
                total,
                cutoff,
            )
        }
    }

    /**
     * Deletes events received before now − retention, in batches of
     * [CookieKeeperProperties.Billing.stripeEventPruneBatchSize], until the window is drained or the per-run
     * cap is hit.
     */
    private fun pruneExpiredEvents() {
        val cutoff = clock.instant().minus(properties.billing.stripeEventRetention)
        val batchSize = properties.billing.stripeEventPruneBatchSize
        val total =
            runBatches(ADVISORY_LOCK_KEY, "Stripe event prune") { size ->
                stripeEventRepository.deleteBatchReceivedBefore(cutoff, size)
            }
        if (total > 0) {
            log.info("Pruned {} Stripe event(s) received before {}", total, cutoff)
        }
    }

    /**
     * Runs [batchOp] in a loop, each call in its own transaction guarded by [lockKey] (mirrors
     * [pruneBatch]'s lock/transaction shape), until a short-of-full batch signals the window is drained
     * or [MAX_BATCHES_PER_RUN] caps a backlog drain at one run. [label] is only for logging. Losing the
     * leader-guard lock on the very first batch means another instance is already running this phase —
     * fall through immediately rather than spin.
     */
    private fun runBatches(
        lockKey: Long,
        label: String,
        batchOp: (batchSize: Int) -> Int,
    ): Int {
        val batchSize = properties.billing.stripeEventPruneBatchSize
        var total = 0
        var batches = 0
        var drained = false
        while (batches < MAX_BATCHES_PER_RUN) {
            when (val result = pruneBatch(lockKey) { batchOp(batchSize) }) {
                BatchResult.LockContended -> {
                    log.debug("Skipping {}; another instance holds the lock", label)
                    return total
                }
                is BatchResult.Processed -> {
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
                "{} hit the {}-batch cap after processing {} event(s); more may remain for the next run",
                label,
                MAX_BATCHES_PER_RUN,
                total,
            )
        }
        return total
    }

    /**
     * Runs one batch in its own transaction: claims the leader-guard advisory lock keyed on [lockKey],
     * and only if it wins runs [batchOp]. The lock and the batch operation share the transaction so the
     * lock is held through it and released at commit.
     */
    private fun pruneBatch(
        lockKey: Long,
        batchOp: () -> Int,
    ): BatchResult =
        transactionTemplate.execute {
            if (!stripeEventRepository.tryAcquireAdvisoryXactLock(lockKey)) {
                BatchResult.LockContended
            } else {
                BatchResult.Processed(batchOp())
            }
        } ?: BatchResult.Processed(0)

    /** Outcome of a single batch — a real row count, or "another instance holds the lock". */
    private sealed interface BatchResult {
        data object LockContended : BatchResult

        data class Processed(
            val count: Int,
        ) : BatchResult
    }

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) serializing the full-row
         * prune across instances. Kept distinct from every other `pg_advisory*` key the app takes (the
         * consent-idempotency and public-scan reapers, and [ADVISORY_LOCK_KEY_REDACT] below).
         */
        internal const val ADVISORY_LOCK_KEY: Long = 8_431_907_662L

        /** Serializes the poison-payload redaction pass across instances; distinct from [ADVISORY_LOCK_KEY]
         * so the two phases never contend for the same lock even if a run somehow overlapped itself. */
        internal const val ADVISORY_LOCK_KEY_REDACT: Long = 8_431_907_663L

        // Safety cap on batches per run so a bug or huge backlog can't spin the scheduler thread
        // indefinitely; leftovers are picked up by the next scheduled run.
        private const val MAX_BATCHES_PER_RUN = 1_000

        // 04:00 daily (server zone) — off the traffic peak and offset from the consent (03:30) and
        // public-scan (03:45) reapers so the nightly sweeps don't contend for IO at the same instant.
        private const val DEFAULT_PRUNE_CRON = "0 0 4 * * *"
    }
}
