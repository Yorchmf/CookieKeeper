package eu.cookiekeeper.consent.dto

/**
 * Response for `GET /api/v1/consent-token/{siteKey}`. [token] is the opaque HMAC-signed origin
 * token the widget attaches to its next consent POST; [expiresInSeconds] echoes the server TTL so
 * the widget knows how long it stays usable (it self-censors well before this to absorb latency).
 *
 * [region] is `"gdpr"`, `"other"` or null — see [eu.cookiekeeper.consent.GdprRegions]. It rides along
 * on this response precisely because the widget already fetches it on the banner path, so the
 * optional region gate costs no extra round trip. Null means "could not tell"; the widget then shows
 * the banner.
 */
data class ConsentTokenResponse(
    val token: String,
    val expiresInSeconds: Long,
    val region: String?,
)
