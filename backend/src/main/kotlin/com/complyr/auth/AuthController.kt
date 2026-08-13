package com.complyr.auth

import com.complyr.auth.dto.ConfirmEmailChangeRequest
import com.complyr.auth.dto.ForgotPasswordRequest
import com.complyr.auth.dto.LoginRequest
import com.complyr.auth.dto.OkResponse
import com.complyr.auth.dto.ResendVerificationRequest
import com.complyr.auth.dto.ResetPasswordRequest
import com.complyr.auth.dto.SignupRequest
import com.complyr.auth.dto.UserResponse
import com.complyr.auth.dto.VerifyEmailRequest
import com.complyr.common.ApiError
import com.complyr.common.ApiResponse
import com.complyr.common.AuthCookies
import com.complyr.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val cookieFactory: AuthCookieFactory,
) {
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(
        @Valid @RequestBody request: SignupRequest,
    ): ApiResponse<UserResponse> = ApiResponse.success(authService.signup(request))

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ResponseEntity<ApiResponse<UserResponse>> {
        val session = authService.login(request)
        return withSessionCookies(session)
    }

    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(AuthCookies.REFRESH_TOKEN, required = false) refreshToken: String?,
    ): ResponseEntity<ApiResponse<UserResponse>> {
        if (refreshToken == null) return clearedSessionUnauthorized(InvalidRefreshTokenException())
        return try {
            withSessionCookies(authService.refresh(refreshToken))
        } catch (ex: InvalidRefreshTokenException) {
            clearedSessionUnauthorized(ex)
        }
    }

    /** Public (no JWT required): an expired access token must never trap the user in a session. */
    @PostMapping("/logout")
    fun logout(
        @CookieValue(AuthCookies.REFRESH_TOKEN, required = false) refreshToken: String?,
    ): ResponseEntity<ApiResponse<OkResponse>> {
        authService.logout(refreshToken)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredAccessCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredSessionMarkerCookie().toString())
            .body(ApiResponse.success(OkResponse()))
    }

    @GetMapping("/me")
    fun me(): ApiResponse<UserResponse> = ApiResponse.success(authService.me(CurrentUser.id()))

    @PostMapping("/verify-email")
    fun verifyEmail(
        @Valid @RequestBody request: VerifyEmailRequest,
    ): ApiResponse<UserResponse> = ApiResponse.success(authService.verifyEmail(request.token))

    @PostMapping("/resend-verification")
    fun resendVerification(
        @Valid @RequestBody request: ResendVerificationRequest,
    ): ApiResponse<OkResponse> {
        authService.resendVerification(request.email)
        return ApiResponse.success(OkResponse())
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ): ApiResponse<OkResponse> {
        authService.forgotPassword(request.email)
        return ApiResponse.success(OkResponse())
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @Valid @RequestBody request: ResetPasswordRequest,
    ): ApiResponse<OkResponse> {
        authService.resetPassword(request.token, request.newPassword)
        return ApiResponse.success(OkResponse())
    }

    /**
     * Confirms an email change from the link mailed to the NEW address (verify-new-first). Public: the
     * clicker holds the mailed token, not necessarily a session, and the token itself is the proof. No
     * session cookies are issued — the swap does not authenticate anyone; any live session keeps working
     * and now belongs to the changed address.
     */
    @PostMapping("/confirm-email-change")
    fun confirmEmailChange(
        @Valid @RequestBody request: ConfirmEmailChangeRequest,
    ): ApiResponse<UserResponse> = ApiResponse.success(authService.confirmEmailChange(request.token))

    private fun withSessionCookies(session: AuthSession): ResponseEntity<ApiResponse<UserResponse>> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, cookieFactory.accessCookie(session.accessToken).toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.refreshCookie(session.refreshToken).toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.sessionMarkerCookie().toString())
            .body(ApiResponse.success(session.user))

    /** Missing/invalid/reused refresh token: clear all session cookies so the client fully logs out. */
    private fun clearedSessionUnauthorized(ex: InvalidRefreshTokenException): ResponseEntity<ApiResponse<UserResponse>> =
        ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredAccessCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredSessionMarkerCookie().toString())
            .body(
                ApiResponse(
                    success = false,
                    data = null,
                    error = ApiError(code = ex.code, message = ex.message ?: "Refresh token is invalid"),
                ),
            )
}
