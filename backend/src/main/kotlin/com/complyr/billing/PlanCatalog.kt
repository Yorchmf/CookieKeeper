package com.complyr.billing

import com.complyr.common.ComplyrProperties
import org.springframework.stereotype.Component

/**
 * The single source of truth for the [Plan] ⇄ Stripe price-id mapping, resolved from the env-bound
 * [ComplyrProperties.Billing.priceIds]. [BillingService] uses it forward (plan → price at Checkout);
 * the webhook handler uses it backward (the price on an inbound subscription → the plan we persist).
 * Keeping both directions here means the mapping is defined once, so the two callers can never drift.
 */
@Component
class PlanCatalog(
    private val properties: ComplyrProperties,
) {
    /** The configured Stripe price id for [plan]; exhaustive so a new plan can't silently miss one. */
    fun priceIdFor(plan: Plan): String =
        with(properties.billing.priceIds) {
            when (plan) {
                Plan.STARTER -> starter
                Plan.PRO -> pro
                Plan.BUSINESS -> business
            }
        }

    /**
     * The [Plan] a Stripe price id maps to, or null when [priceId] is null/blank or matches no
     * configured price (an unrecognized price — e.g. one created directly in the Stripe dashboard).
     * Blank config entries (the empty unit-test defaults) never match, so a blank inbound price can't
     * accidentally resolve to a plan.
     */
    fun planForPriceId(priceId: String?): Plan? {
        if (priceId.isNullOrBlank()) return null
        val ids = properties.billing.priceIds
        return when (priceId) {
            ids.starter.takeIf { it.isNotBlank() } -> Plan.STARTER
            ids.pro.takeIf { it.isNotBlank() } -> Plan.PRO
            ids.business.takeIf { it.isNotBlank() } -> Plan.BUSINESS
            else -> null
        }
    }
}
