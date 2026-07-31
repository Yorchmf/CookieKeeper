package com.complyr.scan

import com.microsoft.playwright.options.Cookie
import java.time.Instant
import java.util.UUID

/**
 * Pure translation from Playwright's [Cookie] to our [ScanCookieEntity], kept out of the crawler so
 * it is unit-testable without a browser. Cookies are de-duplicated by (name, domain) — the schema
 * has no path column, so two cookies differing only by path collapse to one observation.
 */
object ScanCookieMapper {
    const val SESSION_EXPIRY = "session"

    fun toEntities(
        scanId: UUID,
        cookies: List<Cookie>,
    ): List<ScanCookieEntity> =
        cookies
            .distinctBy { it.name to it.domain }
            .map { toEntity(scanId, it) }

    private fun toEntity(
        scanId: UUID,
        cookie: Cookie,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = scanId,
            name = cookie.name,
            domain = cookie.domain,
            expiry = formatExpiry(cookie.expires),
        )

    /**
     * Playwright reports [Cookie.expires] as epoch seconds, or a negative/zero sentinel for a
     * session cookie (no persistent expiry). Persistent expiries are stored as an ISO-8601 instant.
     */
    private fun formatExpiry(expires: Double?): String {
        if (expires == null || expires <= 0.0) return SESSION_EXPIRY
        return Instant.ofEpochSecond(expires.toLong()).toString()
    }
}
