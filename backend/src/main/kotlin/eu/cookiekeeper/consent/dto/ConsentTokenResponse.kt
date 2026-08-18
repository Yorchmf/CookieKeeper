package eu.cookiekeeper.consent.dto

/**
 * Response for `GET /api/v1/consent-token/{siteKey}`. [token] is the opaque HMAC-signed origin
 * token the widget attaches to its next consent POST; [expiresInSeconds] echoes the server TTL so
 * the widget knows how long it stays usable (it self-censors well before this to absorb latency).
 */
data class ConsentTokenResponse(
    val token: String,
    val expiresInSeconds: Long,
)
