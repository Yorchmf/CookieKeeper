package eu.cookiekeeper.account.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Change the account password from inside an authenticated session.
 *
 * [currentPassword] is re-authentication, not a new credential: it is compared against the stored hash, so
 * it carries no complexity policy — echoing the signup rules here would only tell an attacker what to try.
 * Its upper bound is the same bcrypt-input guard [eu.cookiekeeper.account.dto.DeleteAccountRequest] uses, so a
 * multi-megabyte value cannot be fed to the hash before it fails to match.
 *
 * [newPassword] IS a new credential, so it carries the full signup policy (min/max), the max being bcrypt's
 * 72-byte input ceiling. The "new must differ from current" rule is enforced in the service, not here — it
 * needs the stored hash to decide.
 */
data class ChangePasswordRequest(
    @field:NotBlank
    @field:Size(max = MAX_REAUTH_LENGTH)
    val currentPassword: String,
    @field:NotBlank
    @field:Size(min = PASSWORD_MIN, max = PASSWORD_MAX)
    val newPassword: String,
) {
    private companion object {
        const val MAX_REAUTH_LENGTH = 200
        const val PASSWORD_MIN = 10
        const val PASSWORD_MAX = 72
    }
}
