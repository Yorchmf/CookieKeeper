package com.complyr.auth.dto

import com.complyr.auth.UserEntity
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

private const val PASSWORD_MIN = 10
private const val PASSWORD_MAX = 72
private const val EMAIL_MAX = 255
private const val SUPPORTED_LOCALES = "en|de|fr|es|it"

data class SignupRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = EMAIL_MAX)
    val email: String,
    @field:NotBlank
    @field:Size(min = PASSWORD_MIN, max = PASSWORD_MAX)
    val password: String,
    @field:NotBlank
    @field:Pattern(regexp = SUPPORTED_LOCALES, message = "must be one of: en, de, fr, es, it")
    val locale: String,
)

data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

data class VerifyEmailRequest(
    @field:NotBlank
    val token: String,
)

data class ResendVerificationRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)

data class ForgotPasswordRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank
    val token: String,
    @field:NotBlank
    @field:Size(min = PASSWORD_MIN, max = PASSWORD_MAX)
    val newPassword: String,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val locale: String,
    val verifiedAt: Instant?,
) {
    companion object {
        fun from(user: UserEntity): UserResponse =
            UserResponse(id = user.id, email = user.email, locale = user.locale, verifiedAt = user.verifiedAt)
    }
}

/** Body for endpoints that only acknowledge (logout, resend, forgot/reset password). */
data class OkResponse(
    val ok: Boolean = true,
)
