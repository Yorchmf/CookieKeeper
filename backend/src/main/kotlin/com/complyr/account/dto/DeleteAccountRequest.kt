package com.complyr.account.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Re-authentication for an irreversible action. The account's own password is required again even though
 * the session is already authenticated: an unattended logged-in browser must not be one click away from
 * destroying a business's consent records.
 *
 * No complexity constraints — this is a comparison against an existing hash, not a new credential, and
 * echoing the signup policy here would only tell an attacker what to try. The upper bound is not a policy
 * either: it stops a multi-megabyte "password" from being fed to bcrypt (which hashes the whole input
 * before it can fail to match), and is far above any password a real login could have set.
 */
data class DeleteAccountRequest(
    @field:NotBlank
    @field:Size(max = MAX_PASSWORD_LENGTH)
    val password: String,
) {
    private companion object {
        const val MAX_PASSWORD_LENGTH = 200
    }
}
