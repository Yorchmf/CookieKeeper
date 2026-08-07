package com.complyr.scan

import com.microsoft.playwright.options.Cookie
import java.time.Instant
import java.util.UUID

/**
 * Pure translation from Playwright's [Cookie] to our [ScanCookieEntity], kept out of the crawler so
 * it is unit-testable without a browser. Cookies are de-duplicated by (name, domain) — the schema
 * has no path column, so two cookies differing only by path collapse to one observation.
 *
 * The crawl target is attacker-influenced (a verified but hostile site can drop many cookies with
 * long, arbitrary names into an unbounded `text` column), so [toEntities] applies [Caps] before
 * persistence: at most [Caps.maxCookies] rows, each name truncated to [Caps.maxCookieNameLength].
 */
object ScanCookieMapper {
    const val SESSION_EXPIRY = "session"

    /** Abuse bounds applied at the persistence boundary. Required (no permissive default) so a caller
     *  can't accidentally persist an unbounded cookie set. */
    data class Caps(
        val maxCookies: Int,
        val maxCookieNameLength: Int,
    )

    /**
     * The persistable rows plus whether the cap actually dropped observations. [wasCapped] is a
     * truthful operator signal — true only when there were strictly more distinct cookies than
     * [Caps.maxCookies], not merely exactly that many.
     */
    data class Result(
        val rows: List<ScanCookieEntity>,
        val wasCapped: Boolean,
    )

    fun toEntities(
        scanId: UUID,
        cookies: List<Cookie>,
        caps: Caps,
    ): Result {
        // Truncate the name *before* de-dup so two over-long names that share a maxCookieNameLength
        // prefix collapse to one row (they persist identically anyway) instead of slipping past a
        // dedup keyed on the raw name. The input list is already browser-bounded in memory; the row
        // cap below is what bounds persistence and the downstream classify/insert work.
        val distinct =
            cookies
                .asSequence()
                .map { toEntity(scanId, it, caps.maxCookieNameLength) }
                .distinctBy { it.name to it.domain }
                .toList()
        return Result(
            rows = distinct.take(caps.maxCookies),
            wasCapped = distinct.size > caps.maxCookies,
        )
    }

    private fun toEntity(
        scanId: UUID,
        cookie: Cookie,
        maxNameLength: Int,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = scanId,
            name = cookie.name.take(maxNameLength),
            domain = cookie.domain,
            expiry = formatExpiry(cookie.expires),
            // Playwright's flags are nullable Boxed Booleans; a null (flag absent) means the cookie does
            // not carry that protection, so fail closed to false rather than treating absent as set.
            secure = cookie.secure == true,
            httpOnly = cookie.httpOnly == true,
        )

    /**
     * Playwright reports [Cookie.expires] as epoch seconds, or a negative/zero sentinel for a
     * session cookie (no persistent expiry). Persistent expiries are stored as an ISO-8601 instant.
     *
     * [Cookie.expires] is attacker-influenced, and an out-of-range epoch (e.g. a value that saturates
     * `toLong()` to `Long.MAX_VALUE`) makes [Instant.ofEpochSecond] throw [java.time.DateTimeException].
     * Rather than let that unwind the whole scan to dead-letter, treat an unrepresentable expiry as a
     * session cookie — the same fail-closed fallback already used for the missing/sentinel case.
     */
    private fun formatExpiry(expires: Double?): String {
        if (expires == null || expires <= 0.0) return SESSION_EXPIRY
        return runCatching { Instant.ofEpochSecond(expires.toLong()).toString() }.getOrDefault(SESSION_EXPIRY)
    }
}
