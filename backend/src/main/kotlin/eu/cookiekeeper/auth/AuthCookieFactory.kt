package eu.cookiekeeper.auth

import eu.cookiekeeper.common.AuthCookies
import eu.cookiekeeper.common.CookieKeeperProperties
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Builds the HttpOnly session cookies: `cmplyr_at` (access, Path=/), `cmplyr_rt`
 * (refresh, Path=/api/v1/auth) and `cmplyr_session` (non-secret marker, Path=/, lives as long
 * as the refresh token).
 *
 * `Secure` is on by default and comes from configuration rather than the active profile. There are
 * only two profiles now — `dev` and `prd` — and a workstation runs `dev` against `http://localhost`,
 * where a `Secure` cookie is silently dropped and login appears to succeed but never sticks. Making
 * it an explicit opt-out (`COOKIE_SECURE=false`, set only in a workstation `.env`) keeps the unsafe
 * setting a deliberate, greppable act instead of a side effect of which profile happens to be active.
 */
@Component
class AuthCookieFactory(
    private val properties: CookieKeeperProperties,
) {
    private val secure: Boolean = properties.auth.cookieSecure

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
