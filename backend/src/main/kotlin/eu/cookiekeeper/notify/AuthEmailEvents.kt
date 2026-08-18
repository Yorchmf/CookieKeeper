package eu.cookiekeeper.notify

import java.util.UUID

/**
 * Application events published by the auth flows to request an email. Delivery happens
 * after the publishing transaction commits and on a dedicated async executor
 * ([AuthEmailListener]), so SMTP latency or failures can never hold locks, roll back a
 * signup/reset transaction, or leak response-timing signals to the caller.
 */
sealed interface AuthEmailRequested {
    val userId: UUID
    val email: String
    val locale: String
}

data class VerificationEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
    val rawToken: String,
) : AuthEmailRequested

data class PasswordResetEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
    val rawToken: String,
) : AuthEmailRequested

/**
 * Sent once, when an account's email is confirmed for the first time (see
 * [eu.cookiekeeper.auth.AuthService.verifyEmail]). Carries no token — it links to the dashboard, not a
 * one-time action — which is why [rawToken] lives on the token-bearing variants, not this interface.
 */
data class WelcomeEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
) : AuthEmailRequested

/**
 * Confirmation link for an email change, sent to the NEW address (see
 * [eu.cookiekeeper.account.AccountEmailService.requestEmailChange]). [email] is therefore the pending address,
 * not the account's current one; redeeming [rawToken] is what proves control of it and completes the swap.
 */
data class EmailChangeRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
    val rawToken: String,
) : AuthEmailRequested

/**
 * Security heads-up sent to the OLD address after an email change is confirmed (see
 * [eu.cookiekeeper.auth.AuthService.confirmEmailChange]). [email] is the address that just lost the account.
 * Carries no token — it links to the dashboard so an owner who did not initiate the change can react.
 */
data class EmailChangedNoticeRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
) : AuthEmailRequested
