package com.complyr.billing.dto

import com.complyr.billing.Plan
import jakarta.validation.constraints.NotNull

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
