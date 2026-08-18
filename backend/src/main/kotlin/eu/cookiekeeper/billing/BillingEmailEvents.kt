package eu.cookiekeeper.billing

import java.util.UUID

/**
 * Application events published when an account crosses a customer-visible billing boundary — from
 * [BillingWebhookService] for the subscription ones, from [TrialEndingReminderJob] for the trial
 * nudge. Like the auth emails, delivery happens AFTER the publishing transaction commits and on a
 * dedicated async executor ([BillingEmailListener]) — so a slow or broken mail provider can never
 * hold the per-subscription advisory lock, roll back the webhook apply (which would make Stripe
 * redeliver), or delay the 200 we owe Stripe.
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

/**
 * The account's no-card trial ends inside the reminder lead window and it has no live subscription.
 * Published once per account by [TrialEndingReminderJob], which has already claimed the send in the
 * same transaction — so this event firing means the reminder is spoken for, whether or not the mail
 * itself lands.
 */
data class TrialEnding(
    override val userId: UUID,
) : BillingEmailRequested
