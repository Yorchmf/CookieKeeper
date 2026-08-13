package com.complyr.account.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Start an email change from inside an authenticated session ("verify the new address first").
 *
 * [newEmail] is validated for shape and bounded to the `users.email`/`users.pending_email` column width so
 * it can never become a payload. It is NOT proven here — nothing about the account changes until the link
 * mailed to this address is confirmed. Whether it collides with an existing account is decided at swap time
 * (against the unique index), not by echoing a 409 back to the requester.
 *
 * [currentPassword] is re-authentication, not a new credential: it is compared against the stored hash, so
 * it carries no complexity policy (echoing the signup rules would only tell an attacker what to try) and is
 * bounded by the same bcrypt-input guard [ChangePasswordRequest] uses so a multi-megabyte value cannot be
 * fed to the hash before it fails to match. Requiring it here means a stolen session alone cannot redirect
 * a customer's login address.
 */
data class RequestEmailChangeRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = EMAIL_MAX)
    val newEmail: String,
    @field:NotBlank
    @field:Size(max = MAX_REAUTH_LENGTH)
    val currentPassword: String,
) {
    private companion object {
        const val EMAIL_MAX = 255
        const val MAX_REAUTH_LENGTH = 200
    }
}
