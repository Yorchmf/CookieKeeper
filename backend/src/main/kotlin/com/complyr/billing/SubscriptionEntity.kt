package com.complyr.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A user's Stripe subscription (`subscriptions`, V1 baseline + V12 audit columns). One row per user
 * (enforced by `uq_subscriptions_user_id`); the webhook handler upserts it as Stripe fires
 * `checkout.session.completed` / `customer.subscription.*`. [status] is Stripe's raw subscription
 * status string (`active`, `trialing`, `past_due`, `canceled`, …); [isActive] narrows that to the
 * statuses that grant plan access. State updates are immutable `copy(...)` + save (mirrors
 * [com.complyr.policy.PolicySettingsEntity]).
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

    /** Resolve the Stripe subscription id → our row, for `customer.subscription.*` webhook sync. */
    fun findByStripeSubId(stripeSubId: String): SubscriptionEntity?

    /** Resolve the Stripe customer id → our row, for events that carry only the customer. */
    fun findByStripeCustomerId(stripeCustomerId: String): SubscriptionEntity?
}
