package com.complyr.billing

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

/** Parameters for a subscription Checkout session. */
data class CheckoutRequest(
    val priceId: String,
    val customer: CheckoutCustomer,
    val successUrl: String,
    val cancelUrl: String,
    val automaticTax: Boolean,
)

/**
 * Thin port over the Stripe SDK so [BillingService] stays SDK-agnostic and unit-testable against a
 * fake. Implementations translate the Stripe SDK's checked failures into
 * [BillingUnavailableException] and must never leak Stripe error detail (which can carry customer
 * PII) to the caller or logs beyond the request id.
 */
interface StripeGateway {
    /** Creates a subscription Checkout session and returns its hosted redirect URL. */
    fun createCheckoutSession(request: CheckoutRequest): String

    /** Creates a Customer Portal session for [customerId] and returns its redirect URL. */
    fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String
}
