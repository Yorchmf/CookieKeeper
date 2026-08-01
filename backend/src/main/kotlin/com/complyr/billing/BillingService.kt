package com.complyr.billing

import com.complyr.auth.UserRepository
import com.complyr.common.ComplyrProperties
import com.complyr.common.UnauthenticatedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Orchestrates the two hosted Stripe billing flows for an authenticated user:
 *
 *  - [startCheckout] — a subscription Checkout session for a chosen [Plan]. An existing Stripe
 *    customer is reused when the user already has a subscription row (so they never accumulate
 *    duplicate customers); otherwise Stripe creates one from the user's email during Checkout. The
 *    subscription row itself is created/synced later by the webhook handler (Slice 3), so this path
 *    writes nothing — it only mints a redirect URL.
 *  - [openPortal] — a Customer Portal session (manage/cancel/switch plan). Requires an existing
 *    Stripe customer, so it fails with [NoBillingAccountException] for users who never subscribed.
 *
 * All Stripe I/O goes through [StripeGateway]; this service holds only the mapping/URL logic and is
 * unit-tested against a fake gateway.
 */
@Service
class BillingService(
    private val gateway: StripeGateway,
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val planCatalog: PlanCatalog,
    private val properties: ComplyrProperties,
) {
    fun startCheckout(
        userId: UUID,
        plan: Plan,
    ): String {
        val billing = properties.billing
        val existingCustomerId = subscriptionRepository.findByUserId(userId)?.stripeCustomerId
        // Reuse the existing Stripe customer when there is one; only load the user (for the email
        // Stripe needs to create a customer) on the first-time path, and never send both — Stripe
        // rejects a request carrying an existing customer AND a customer_email.
        val customer =
            if (existingCustomerId != null) {
                CheckoutCustomer.Existing(existingCustomerId)
            } else {
                val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
                CheckoutCustomer.New(user.email)
            }
        val request =
            CheckoutRequest(
                userId = userId,
                priceId = planCatalog.priceIdFor(plan),
                customer = customer,
                successUrl = properties.appBaseUrl + billing.checkoutSuccessPath,
                cancelUrl = properties.appBaseUrl + billing.checkoutCancelPath,
                automaticTax = billing.automaticTax,
            )
        return gateway.createCheckoutSession(request)
    }

    fun openPortal(userId: UUID): String {
        val customerId =
            subscriptionRepository.findByUserId(userId)?.stripeCustomerId
                ?: throw NoBillingAccountException()
        return gateway.createPortalSession(
            customerId = customerId,
            returnUrl = properties.appBaseUrl + properties.billing.portalReturnPath,
        )
    }
}
