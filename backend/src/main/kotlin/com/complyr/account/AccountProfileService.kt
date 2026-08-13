package com.complyr.account

import com.complyr.auth.UserRepository
import com.complyr.auth.dto.UserResponse
import com.complyr.common.UnauthenticatedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Self-service account mutations behind `/settings/profile`: display name today, with password and email
 * changes joining as their sub-slices land. Every method is scoped to the caller's own id — there is no
 * user id on any request path. Art. 17 tombstones are rejected upstream by `ErasedAccountFilter` (the single
 * enforcement point), so a still-valid access JWT minted moments before an erasure never reaches here.
 */
@Service
class AccountProfileService(
    private val userRepository: UserRepository,
) {
    /**
     * Sets (or clears) the display name. A blank or whitespace-only input clears it to null rather than
     * storing an empty string, so "no name" has exactly one representation. Returns the refreshed user so
     * the caller can seed its identity cache without a second round trip.
     */
    @Transactional
    fun updateName(
        userId: UUID,
        name: String?,
    ): UserResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        return UserResponse.from(userRepository.save(user.copy(name = name?.let(::sanitizeName))))
    }

    /**
     * Strips control and Unicode format characters — C0/C1 controls, newlines, bidi overrides (U+202E),
     * zero-width joiners — before trimming, then folds blank to null. The name is destined for
     * transactional-email greetings, a plain-text sink where a newline is header injection and a bidi
     * override is display spoofing; React escapes it in the dashboard but the email boundary does not.
     */
    private fun sanitizeName(raw: String): String? =
        raw
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
            .takeIf { it.isNotEmpty() }
}
