package com.complyr.account.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Re-authentication for "sign out of all devices". The account's own password is required again even though
 * the session is already authenticated: an unattended logged-in browser must not be one click away from
 * revoking the account's other sessions (and dropping this one), and confirming with the password is the same
 * bar the change-password and erasure flows set for their side effects.
 *
 * No complexity constraints — this is a comparison against an existing hash, not a new credential, and
 * echoing the signup policy here would only tell an attacker what to try. The upper bound is the same
 * bcrypt-input guard the other re-authentication DTOs use: it stops a multi-megabyte value from being fed to
 * the hash before it can fail to match, and is far above any password a real login could have set.
 */
data class RevokeAllSessionsRequest(
    @field:NotBlank
    @field:Size(max = MAX_REAUTH_LENGTH)
    val currentPassword: String,
) {
    private companion object {
        const val MAX_REAUTH_LENGTH = 200
    }
}
