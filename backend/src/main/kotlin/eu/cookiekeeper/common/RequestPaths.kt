package eu.cookiekeeper.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.util.UrlPathHelper

/**
 * Resolves the request path used for rate-limit **tier matching** — the decoded, matrix-stripped,
 * context-relative path, i.e. the same string Spring routes on.
 *
 * The raw `request.requestURI` is NOT percent-decoded (Servlet spec), but Spring's `PathPattern`
 * matching runs on the per-segment **decoded** path. Classifying on the raw URI therefore lets a
 * percent-encoded request be dispatched to one controller while the rate limiter sees a different
 * string — a path-confusion bypass. Concretely, keying tiers off the raw URI let
 * `POST /api/v1/%62illing/checkout-session` (`%62`=`b`) be classified as the generous GENERAL tier
 * (or, with an encoded prefix, matched by nothing and skipped entirely) while still hitting the real
 * billing controller. Decoding here closes that gap for both the pre-auth IP filter and the post-auth
 * per-user filter. (Dangerous encodings — `%2f`, `%2e`, `%3b`, `\`, `//` — are rejected upstream by
 * Spring Security's `StrictHttpFirewall`, so only benign alphanumeric encodings ever reach decoding.)
 */
object RequestPaths {
    // urlDecode + removeSemicolonContent are UrlPathHelper defaults; set explicitly so a future
    // default change can't silently reopen the confusion window. removeSemicolonContent also strips
    // matrix params (`;jsessionid=…`), replacing the previous manual `substringBefore(';')`.
    private val pathHelper =
        UrlPathHelper().apply {
            setUrlDecode(true)
            setRemoveSemicolonContent(true)
        }

    /** Decoded, matrix-stripped, context-relative path for tier matching. */
    fun tierPath(request: HttpServletRequest): String = pathHelper.getPathWithinApplication(request)
}
