package com.complyr.billing

import java.util.UUID

/**
 * Application events published by [BillingWebhookService] when an account's subscription crosses a
 * customer-visible billing boundary. Like the auth emails, delivery happens AFTER the webhook
 * transaction commits and on a dedicated async executor ([BillingEmailListener]) — so a slow or
 * broken mail provider can never hold the per-subscription advisory lock, roll back the webhook
 * apply (which would make Stripe redeliver), or delay the 200 we owe Stripe.
 *
 * Events carry only the [userId]; the recipient email + locale are resolved fresh from the user
 * row at send time ([BillingNotifier]), never captured here.
 */
sealed interface BillingEmailRequested {
    val userId: UUID
}

/** The account's subscription just became active (first purchase, or recovery from a lapse). */
data class SubscriptionActivated(
    override val userId: UUID,
    val plan: Plan,
) : BillingEmailRequested

/** A payment failed and the subscription entered `past_due` — the user must fix their payment method. */
data class PaymentIssue(
    override val userId: UUID,
) : BillingEmailRequested
