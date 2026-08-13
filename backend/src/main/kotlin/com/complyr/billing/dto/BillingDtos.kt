package com.complyr.billing.dto

import com.complyr.billing.AccountEntitlement
import com.complyr.billing.EntitlementSummary
import com.complyr.billing.Entitlements
import com.complyr.billing.Plan
import jakarta.validation.constraints.NotNull
import java.time.Instant

/**
 * Body for `POST /api/v1/billing/checkout-session`. [plan] is the enum NAME (STARTER/PRO/BUSINESS);
 * an unknown value fails Jackson deserialization (400), a null one fails [NotNull] validation (400).
 */
data class CheckoutSessionRequest(
    @field:NotNull(message = "plan is required")
    val plan: Plan?,
)

/** Redirect URL the dashboard sends the browser to (Stripe-hosted Checkout / Portal). */
data class CheckoutSessionResponse(
    val url: String,
)

data class PortalSessionResponse(
    val url: String,
)

/**
 * The account's billing state for `GET /api/v1/billing/entitlement`, powering the dashboard billing
 * page. [state] is the stable contract the dashboard maps to a localized label — never surface raw
 * backend text. [plan] is the purchased plan's enum name when [state] is `subscribed` (null while
 * trialing or expired, since the trial is only Starter-*shaped*, not a bought plan); [trialEndsAt] is
 * set only while trialing. [activeSites] is current usage against [limits].maxSites so the page can
 * show "1 of 1 sites used" and motivate the upgrade CTA. [consentEventsUsed] is the trial ingestion
 * count against [limits].consentEventCap — non-null only while trialing, so the dashboard shows the
 * usage meter during the trial and hides it otherwise. It is a display signal, never an ingestion gate
 * (CLAUDE.md #3).
 */
data class EntitlementResponse(
    val state: String,
    val plan: String?,
    val trialEndsAt: Instant?,
    val activeSites: Long,
    val consentEventsUsed: Long?,
    val limits: EntitlementLimits,
) {
    companion object {
        private const val STATE_TRIAL = "trial"
        private const val STATE_SUBSCRIBED = "subscribed"
        private const val STATE_EXPIRED = "expired"

        fun from(summary: EntitlementSummary): EntitlementResponse {
            val entitlement = summary.entitlement
            // Exhaustive over the sealed hierarchy — a new variant forces a compile error here.
            val (state, plan, trialEndsAt) =
                when (entitlement) {
                    is AccountEntitlement.Trial -> Triple(STATE_TRIAL, null, entitlement.endsAt)
                    is AccountEntitlement.Subscribed -> Triple(STATE_SUBSCRIBED, entitlement.plan.name, null)
                    AccountEntitlement.Expired -> Triple(STATE_EXPIRED, null, null)
                }
            return EntitlementResponse(
                state = state,
                plan = plan,
                trialEndsAt = trialEndsAt,
                activeSites = summary.activeSites,
                consentEventsUsed = summary.consentEventsUsed,
                limits = EntitlementLimits.from(entitlement.entitlements),
            )
        }
    }
}

/** Effective per-account limits (see [com.complyr.billing.Entitlements]); retention is exposed as whole months. */
data class EntitlementLimits(
    val maxSites: Int,
    val rescanFrequency: String,
    val onDemandRescan: Boolean,
    val priorityScan: Boolean,
    val removeBranding: Boolean,
    val csvExport: Boolean,
    val consentRetentionMonths: Long,
    val consentEventCap: Long?,
) {
    companion object {
        fun from(entitlements: Entitlements): EntitlementLimits =
            EntitlementLimits(
                maxSites = entitlements.maxSites,
                rescanFrequency = entitlements.rescanFrequency.name.lowercase(),
                onDemandRescan = entitlements.onDemandRescan,
                priorityScan = entitlements.priorityScan,
                removeBranding = entitlements.removeBranding,
                csvExport = entitlements.csvExport,
                consentRetentionMonths = entitlements.consentRetention.toTotalMonths(),
                consentEventCap = entitlements.consentEventCap,
            )
    }
}
