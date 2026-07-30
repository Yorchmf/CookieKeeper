package com.complyr.auth

import com.complyr.common.AuthCookies
import com.complyr.common.ComplyrProperties
import org.springframework.core.env.Environment
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Builds the HttpOnly session cookies: `cmplyr_at` (access, Path=/), `cmplyr_rt`
 * (refresh, Path=/api/v1/auth) and `cmplyr_session` (non-secret marker, Path=/, lives as long
 * as the refresh token). `Secure` everywhere except the local profile.
 */
@Component
class AuthCookieFactory(
    private val properties: ComplyrProperties,
    environment: Environment,
) {
    private val secure: Boolean = "local" !in environment.activeProfiles

    fun accessCookie(token: String): ResponseCookie =
        build(AuthCookies.ACCESS_TOKEN, token, path = "/", maxAge = properties.auth.accessTokenTtl)

    fun refreshCookie(token: String): ResponseCookie =
        build(AuthCookies.REFRESH_TOKEN, token, path = AuthCookies.REFRESH_PATH, maxAge = properties.auth.refreshTokenTtl)

    fun sessionMarkerCookie(): ResponseCookie =
        build(AuthCookies.SESSION_MARKER, AuthCookies.SESSION_MARKER_VALUE, path = "/", maxAge = properties.auth.refreshTokenTtl)

    fun expiredAccessCookie(): ResponseCookie = build(AuthCookies.ACCESS_TOKEN, "", path = "/", maxAge = Duration.ZERO)

    fun expiredRefreshCookie(): ResponseCookie =
        build(AuthCookies.REFRESH_TOKEN, "", path = AuthCookies.REFRESH_PATH, maxAge = Duration.ZERO)

    fun expiredSessionMarkerCookie(): ResponseCookie = build(AuthCookies.SESSION_MARKER, "", path = "/", maxAge = Duration.ZERO)

    private fun build(
        name: String,
        value: String,
        path: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie
            .from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build()
}
