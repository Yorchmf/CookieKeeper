package eu.cookiekeeper.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * A user's Stripe subscription (`subscriptions`, V1 baseline + V12 audit columns). One row per user
 * (enforced by `uq_subscriptions_user_id`); the webhook handler upserts it as Stripe fires
 * `checkout.session.completed` / `customer.subscription.*`. [status] is Stripe's raw subscription
 * status string (`active`, `trialing`, `past_due`, `canceled`, …); [isActive] narrows that to the
 * statuses that grant plan access. State updates are immutable `copy(...)` + save (mirrors
 * [eu.cookiekeeper.policy.PolicySettingsEntity]).
 */
@Entity
@Table(name = "subscriptions")
data class SubscriptionEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    @Column(name = "stripe_customer_id")
    val stripeCustomerId: String?,
    @Column(name = "stripe_sub_id")
    val stripeSubId: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false)
    val plan: Plan,
    @Column(name = "status", nullable = false)
    val status: String,
    @Column(name = "period_end")
    val periodEnd: Instant?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    // Stripe `created` of the last subscription event applied to this row (V13). The webhook handler
    // skips any event strictly OLDER than this (a reordered redelivery) so it can't clobber newer
    // state; same-second ties are applied in arrival order under a per-subscription lock. Null until
    // the first event stamps it.
    @Column(name = "stripe_event_at")
    val stripeEventAt: Instant? = null,
    // The tracked subscription's OWN `created` (V29) — fixed at Stripe subscription creation, unlike
    // [stripeEventAt] which is when the last APPLIED EVENT fired. BillingWebhookService compares an
    // incoming event's subscription lineage against this, not against [stripeEventAt], to tell a
    // genuinely newer replacement subscription apart from a straggling event on an already-superseded
    // one — an event-delivery-time watermark alone cannot make that distinction. Null only for rows
    // written before this column existed; they accept the next event unconditionally, which then
    // stamps it.
    @Column(name = "stripe_sub_created_at")
    val stripeSubCreatedAt: Instant? = null,
) {
    /** True for the Stripe statuses that entitle the user to their plan. */
    val isActive: Boolean get() = status in ACTIVE_STATUSES

    companion object {
        /** Stripe subscription statuses that grant plan access (`trialing` = card-on-file Stripe trial). */
        val ACTIVE_STATUSES = setOf("active", "trialing")
    }
}

interface SubscriptionRepository : JpaRepository<SubscriptionEntity, UUID> {
    /** The account's single subscription, for entitlement resolution and the billing page. */
    fun findByUserId(userId: UUID): SubscriptionEntity?

    /**
     * The subscriptions for a batch of users in one query, for [EntitlementService.resolveAll] — the
     * scheduled re-scan job resolves a whole candidate batch without an N+1. Users with no subscription
     * row simply have no entry (the trial/expired path in [PlanResolver] handles them).
     */
    fun findAllByUserIdIn(userIds: Collection<UUID>): List<SubscriptionEntity>

    /** Resolve the Stripe subscription id → our row, for `customer.subscription.*` webhook sync. */
    fun findByStripeSubId(stripeSubId: String): SubscriptionEntity?

    /** Resolve the Stripe customer id → our row, for events that carry only the customer. */
    fun findByStripeCustomerId(stripeCustomerId: String): SubscriptionEntity?

    /**
     * Transaction-scoped Postgres advisory lock keyed on the ACCOUNT (see
     * [BillingWebhookService.accountLockKey]), taken before the read-modify-write in
     * [BillingWebhookService.applySubscription] so two concurrently-delivered events for this
     * account — even ones naming two DIFFERENT Stripe subscriptions — serialize instead of both
     * reading the old row and racing to save (last-writer-wins, which could strand e.g. `active` after
     * `canceled`, or let one subscription's write clobber another's). Released automatically at
     * commit/rollback; the wrapping `SELECT count(*)` just gives the native query a mappable non-void
     * result (mirrors PolicyService's site lock).
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireSubscriptionLock(
        @Param("key") key: Long,
    ): Long
}
