package com.complyr.notify

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
 * [com.complyr.auth.AuthService.verifyEmail]). Carries no token — it links to the dashboard, not a
 * one-time action — which is why [rawToken] lives on the token-bearing variants, not this interface.
 */
data class WelcomeEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
) : AuthEmailRequested
