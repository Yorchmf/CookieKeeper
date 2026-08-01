package com.complyr.billing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Applies verified Stripe webhooks to our billing state, exactly once, in the face of Stripe's
 * at-least-once, out-of-order delivery. The flow is an inbox pattern in two committed transactions:
 *
 *  1. **Record** ([recordInbox]) — `INSERT ... ON CONFLICT (stripe_event_id) DO NOTHING`, committed
 *     on its own. This durably logs the raw body (with its PII) so a later apply-crash can retry, and
 *     atomically claims the event id: a re-delivery inserts nothing.
 *  2. **Apply** ([applyIfUnprocessed]) — in a second transaction, re-read the row; if it is already
 *     `processed_at`-stamped, do nothing (a duplicate we already handled). Otherwise apply the
 *     subscription state and, in the same transaction, stamp `processed_at` while NULLing the payload
 *     (redact-on-process). If the apply throws, this transaction rolls back: the row and its body
 *     survive from step 1, `processed_at` stays null, and Stripe's redelivery reprocesses it.
 *
 * Splitting the two means a failed apply never loses the payload, and the `processed_at IS NULL` gate
 * plus idempotent absolute-state upsert make concurrent double-processing safe (both apply the same
 * final state; only one stamps). Signature verification happens upstream in [StripeGateway]; a bad
 * signature never reaches here. [clock] is injected for deterministic time.
 */
@Service
class BillingWebhookService(
    private val gateway: StripeGateway,
    private val stripeEventRepository: StripeEventRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planCatalog: PlanCatalog,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(BillingWebhookService::class.java)

    // Two short, independently-committed transactions (record, then apply) rather than one @Transactional
    // span — so a failed apply keeps the recorded payload for Stripe's retry (see the class doc).
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Verify, record, then apply one raw webhook. A bad signature throws [WebhookSignatureException]
     * (→ 400) before anything is recorded. A genuine apply failure (e.g. the DB drops mid-apply)
     * propagates out as a 500: transaction 1 has already durably recorded the payload and
     * transaction 2 rolled back leaving `processed_at` null, so Stripe's redelivery reprocesses it.
     * Only a fully applied — or deliberately ignored/dropped — event returns normally (→ 200),
     * telling Stripe to stop retrying.
     */
    fun handle(
        payload: String,
        signatureHeader: String,
    ) {
        val event = gateway.parseWebhookEvent(payload, signatureHeader)
        recordInbox(event)
        applyIfUnprocessed(event)
    }

    /** Transaction 1: durably log + claim the event id (idempotent on re-delivery). */
    private fun recordInbox(event: StripeWebhookEvent) {
        transactionTemplate.executeWithoutResult {
            stripeEventRepository.insertIfAbsent(
                id = UUID.randomUUID(),
                stripeEventId = event.id,
                type = event.type,
                payload = event.payload,
                receivedAt = clock.instant(),
            )
        }
    }

    /** Transaction 2: apply the event once (gated on `processed_at IS NULL`) and stamp+redact. */
    private fun applyIfUnprocessed(event: StripeWebhookEvent) {
        transactionTemplate.executeWithoutResult {
            val recorded = stripeEventRepository.findByStripeEventId(event.id)
            if (recorded == null) {
                // recordInbox just committed this id, so absence means a concurrent reaper/delete — nothing to do.
                log.warn("Stripe event vanished between record and apply; skipping type={}", event.type)
                return@executeWithoutResult
            }
            if (recorded.processedAt != null) {
                // A re-delivery of an event we already applied. Nothing to do — inbox did its job.
                return@executeWithoutResult
            }
            when (val data = event.data) {
                is StripeEventData.SubscriptionChanged -> applySubscription(data, event.created)
                StripeEventData.Ignored ->
                    log.info("Recording no-op Stripe event type={}", event.type)
            }
            stripeEventRepository.markProcessedAndRedact(event.id, clock.instant())
        }
    }

    /**
     * Upsert the account's single subscription row from a `customer.subscription.*` event. A blocking,
     * transaction-scoped advisory lock keyed on the subscription is taken FIRST, so a concurrent
     * delivery of another event for the same subscription waits and then reads our just-committed
     * state — closing the read-modify-write lost-update window the timestamp watermark alone can't
     * (two threads could both read the old watermark and both save, last-writer-wins). Linking then
     * prefers the Stripe subscription id, then the metadata user id we stamped at Checkout, then the
     * customer id — so an event is attributed even before our row carries the sub id.
     */
    private fun applySubscription(
        data: StripeEventData.SubscriptionChanged,
        eventCreatedAt: Instant,
    ) {
        // Serialize all processing for THIS subscription before any read (see the method doc).
        subscriptionRepository.acquireSubscriptionLock(advisoryLockKey(data.subscriptionId))

        val existing = resolveExisting(data)
        if (isOutOfOrder(existing, eventCreatedAt)) {
            log.info("Skipping out-of-order Stripe subscription event (older than last applied)")
            return
        }

        // Unknown/absent price → keep the current plan if we have a row, else the lowest tier (fail
        // closed on entitlements). A real event from our Checkout always carries a configured price.
        val plan = planCatalog.planForPriceId(data.priceId) ?: existing?.plan ?: Plan.STARTER
        val row = upsertRow(existing, data, plan, eventCreatedAt) ?: return
        subscriptionRepository.save(row)
    }

    /** Locate the account's row from the event, most-specific link first. */
    private fun resolveExisting(data: StripeEventData.SubscriptionChanged): SubscriptionEntity? =
        subscriptionRepository.findByStripeSubId(data.subscriptionId)
            ?: data.userId?.let { subscriptionRepository.findByUserId(it) }
            ?: data.customerId?.let { subscriptionRepository.findByStripeCustomerId(it) }

    /**
     * True when this event is STRICTLY older than the last one applied to the row — a reordered
     * redelivery we must not let clobber newer state. Ties are NOT out-of-order: Stripe's `created`
     * is epoch-*seconds*, so two distinct events (e.g. an `updated` immediately followed by a
     * `deleted` on instant cancel) can share a second; dropping the second would strand a canceled
     * sub as active. Under the per-subscription lock in [applySubscription], same-second events apply
     * in arrival order (≈ creation order), so last-writer-wins yields the correct final state.
     */
    private fun isOutOfOrder(
        existing: SubscriptionEntity?,
        eventCreatedAt: Instant,
    ): Boolean = existing?.stripeEventAt?.let { eventCreatedAt.isBefore(it) } ?: false

    /**
     * Build the row to save: an immutable `copy(...)` of [existing], or a fresh entity on the first
     * event (see [newSubscription], which returns null for an unattributable event — logged and
     * dropped, never turned into an orphan row; the outer transaction still stamps it processed so
     * Stripe stops redelivering).
     */
    private fun upsertRow(
        existing: SubscriptionEntity?,
        data: StripeEventData.SubscriptionChanged,
        plan: Plan,
        eventCreatedAt: Instant,
    ): SubscriptionEntity? {
        val now = clock.instant()
        return existing?.copy(
            stripeCustomerId = data.customerId ?: existing.stripeCustomerId,
            stripeSubId = data.subscriptionId,
            plan = plan,
            status = data.status,
            periodEnd = data.currentPeriodEnd ?: existing.periodEnd,
            updatedAt = now,
            stripeEventAt = eventCreatedAt,
        ) ?: newSubscription(data, plan, eventCreatedAt, now)
    }

    /** A first-event subscription row, or null when the event carries no user id to attribute it to. */
    private fun newSubscription(
        data: StripeEventData.SubscriptionChanged,
        plan: Plan,
        eventCreatedAt: Instant,
        now: Instant,
    ): SubscriptionEntity? {
        val userId = data.userId
        if (userId == null) {
            log.warn("Unattributable Stripe subscription event (no row, no user metadata); dropping")
            return null
        }
        return SubscriptionEntity(
            userId = userId,
            stripeCustomerId = data.customerId,
            stripeSubId = data.subscriptionId,
            plan = plan,
            status = data.status,
            periodEnd = data.currentPeriodEnd,
            createdAt = now,
            updatedAt = now,
            stripeEventAt = eventCreatedAt,
        )
    }

    private companion object {
        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 64-bit FNV-1a offset basis
        private const val FNV_PRIME = 0x100000001b3L
        private const val BYTE_MASK = 0xffL
    }

    /**
     * Fold a Stripe subscription id into the 64-bit key `pg_advisory_xact_lock` takes (64-bit FNV-1a).
     * A rare collision only makes two unrelated subscriptions serialize briefly — correctness holds.
     */
    private fun advisoryLockKey(subscriptionId: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in subscriptionId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash
    }
}
