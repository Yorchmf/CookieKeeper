package eu.cookiekeeper.account.dto

import jakarta.validation.constraints.Size

/**
 * Update the account holder's display name. The name is optional end to end: `null` or a blank string
 * clears it, and the account falls back to its email everywhere the name would show. The bound is measured
 * on the raw request string (leading/trailing whitespace and any control characters count against it) — an
 * upfront guard matching the `users.name` column (V23); the service then strips control/format characters,
 * trims, and folds blank to null, so the stored value is always at or under the column width.
 */
data class UpdateProfileRequest(
    @field:Size(max = NAME_MAX)
    val name: String?,
) {
    private companion object {
        const val NAME_MAX = 120
    }
}
