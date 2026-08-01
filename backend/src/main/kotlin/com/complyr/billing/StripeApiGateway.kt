package com.complyr.billing

import com.stripe.StripeClient
import com.stripe.exception.StripeException
import com.stripe.param.checkout.SessionCreateParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams

/**
 * [StripeGateway] backed by the real Stripe SDK ([StripeClient], the instance-based v33 API — not the
 * deprecated global `Stripe.apiKey` statics). Kept intentionally thin: it only maps our request
 * shapes onto the SDK builders and translates the SDK's checked [StripeException] into a generic
 * [BillingUnavailableException]. On failure it logs the Stripe request id / status / code only —
 * never the exception message or payload, which can carry the customer id or email (CLAUDE.md #4).
 */
@Service
class StripeApiGateway(
    private val stripeClient: StripeClient,
) : StripeGateway {
    private val log = LoggerFactory.getLogger(StripeApiGateway::class.java)

    override fun createCheckoutSession(request: CheckoutRequest): String {
        val params =
            SessionCreateParams
                .builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .apply {
                    // Sealed CheckoutCustomer guarantees exactly one branch; Stripe rejects both set.
                    when (val customer = request.customer) {
                        is CheckoutCustomer.Existing -> setCustomer(customer.customerId)
                        is CheckoutCustomer.New -> setCustomerEmail(customer.email)
                    }
                }.setSuccessUrl(request.successUrl)
                .setCancelUrl(request.cancelUrl)
                .addLineItem(
                    SessionCreateParams.LineItem
                        .builder()
                        .setPrice(request.priceId)
                        .setQuantity(1L)
                        .build(),
                ).setAutomaticTax(
                    SessionCreateParams.AutomaticTax
                        .builder()
                        .setEnabled(request.automaticTax)
                        .build(),
                ).build()
        return try {
            stripeClient
                .v1()
                .checkout()
                .sessions()
                .create(params)
                .url ?: throw BillingUnavailableException()
        } catch (e: StripeException) {
            logStripeFailure("checkout session", e)
            throw BillingUnavailableException()
        }
    }

    override fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String {
        val params =
            PortalSessionCreateParams
                .builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build()
        return try {
            stripeClient
                .v1()
                .billingPortal()
                .sessions()
                .create(params)
                .url ?: throw BillingUnavailableException()
        } catch (e: StripeException) {
            logStripeFailure("portal session", e)
            throw BillingUnavailableException()
        }
    }

    private fun logStripeFailure(
        operation: String,
        e: StripeException,
    ) {
        // Request id + status + code are safe to log and are what Stripe support needs; the message
        // and body are NOT logged (they can echo the customer id/email).
        log.error(
            "Stripe {} create failed (status={}, code={}, requestId={})",
            operation,
            e.statusCode,
            e.code,
            e.requestId,
        )
    }
}
