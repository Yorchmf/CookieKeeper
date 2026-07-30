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
    val rawToken: String
}

data class VerificationEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
    override val rawToken: String,
) : AuthEmailRequested

data class PasswordResetEmailRequested(
    override val userId: UUID,
    override val email: String,
    override val locale: String,
    override val rawToken: String,
) : AuthEmailRequested
