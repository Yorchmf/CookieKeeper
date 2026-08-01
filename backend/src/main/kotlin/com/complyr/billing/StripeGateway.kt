package com.complyr.billing

import java.time.Instant
import java.util.UUID

/** Stripe subscription metadata key carrying our account id, set at Checkout and read from webhooks. */
const val STRIPE_METADATA_USER_ID = "complyr_user_id"

/**
 * Which Stripe customer a Checkout session bills. Modeled as a sealed type so "exactly one of an
 * existing customer id OR an email for Stripe to create one" is a compile-time guarantee rather than
 * a two-nullable-fields convention (Stripe rejects a request that carries both).
 */
sealed interface CheckoutCustomer {
    /** Reuse an existing Stripe customer (the account already has one) so no duplicate is created. */
    data class Existing(
        val customerId: String,
    ) : CheckoutCustomer

    /** No customer yet — Stripe creates one from this email during Checkout. */
    data class New(
        val email: String,
    ) : CheckoutCustomer
}

/**
 * Parameters for a subscription Checkout session. [userId] is stamped onto the subscription's
 * metadata ([STRIPE_METADATA_USER_ID]) so every later `customer.subscription.*` webhook is self-
 * contained — the handler recovers the account without a customer→user lookup that could race the
 * `checkout.session.completed` event.
 */
data class CheckoutRequest(
    val userId: UUID,
    val priceId: String,
    val customer: CheckoutCustomer,
    val successUrl: String,
    val cancelUrl: String,
    val automaticTax: Boolean,
)

/** A signature-verified Stripe webhook, reduced to the fields the handler acts on. */
data class StripeWebhookEvent(
    val id: String,
    val type: String,
    val created: Instant,
    val payload: String,
    val data: StripeEventData,
)

/** The actionable content of a webhook, narrowed from Stripe's open event universe. */
sealed interface StripeEventData {
    /**
     * A `customer.subscription.created/updated/deleted` event — the subscription's current state.
     * [userId] comes from the subscription metadata we set at Checkout ([STRIPE_METADATA_USER_ID]);
     * it is null when absent or unparseable. [status] is Stripe's raw status (`active`, `canceled`,
     * …); a `deleted` event simply arrives as `canceled`, so no separate flag is needed.
     */
    data class SubscriptionChanged(
        val userId: UUID?,
        val subscriptionId: String,
        val customerId: String?,
        val status: String,
        val priceId: String?,
        val currentPeriodEnd: Instant?,
    ) : StripeEventData

    /** Any event type we log for idempotency/audit but take no action on. */
    data object Ignored : StripeEventData
}

/**
 * Thin port over the Stripe SDK so [BillingService] / [BillingWebhookService] stay SDK-agnostic and
 * unit-testable against a fake. Implementations translate the Stripe SDK's checked failures into our
 * typed exceptions and must never leak Stripe error detail (which can carry customer PII) to the
 * caller or logs beyond the request id.
 */
interface StripeGateway {
    /** Creates a subscription Checkout session and returns its hosted redirect URL. */
    fun createCheckoutSession(request: CheckoutRequest): String

    /** Creates a Customer Portal session for [customerId] and returns its redirect URL. */
    fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String

    /**
     * Verifies the webhook signature against the raw [payload] and returns the reduced event. Throws
     * [WebhookSignatureException] when the signature is missing/invalid — the only client-visible
     * failure of the webhook endpoint.
     */
    fun parseWebhookEvent(
        payload: String,
        signatureHeader: String,
    ): StripeWebhookEvent
}
