package eu.cookiekeeper.consent

import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.consent.dto.ConsentEventRequest
import eu.cookiekeeper.consent.dto.ConsentTokenResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Mints a short-lived, stateless HMAC origin token for the public consent path
 * (`GET /api/v1/consent-token/{siteKey}`). Unauthenticated and CORS-open like consent itself, and
 * rate-limited on the CONSENT tier by [eu.cookiekeeper.common.RateLimitFilter]. The token binds the site
 * key and the request's `Origin` header so a captured consent payload can't be replayed past the TTL
 * nor reused from another origin — defence-in-depth, not an origin proof (see [ConsentOriginToken]).
 *
 * Deliberately does NO database work: it does not check that the site exists (an unknown site key
 * yields a token that simply fails the consent endpoint's ACTIVE-site lookup), which keeps issuance
 * cheap, stateless, and free of a site-enumeration oracle. The response is marked `no-store` so no
 * proxy or browser cache ever serves a stale or shared token.
 */
@RestController
@RequestMapping("/api/v1/consent-token")
class ConsentTokenController(
    private val consentOriginToken: ConsentOriginToken,
) {
    @GetMapping("/{siteKey}")
    fun mint(
        @PathVariable siteKey: String,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<ApiResponse<ConsentTokenResponse>> {
        // Bound the signed input: never sign an arbitrarily long path segment. An over-long key can
        // never match a real site, so reject it as a malformed request (a bad *request*, not a bad
        // *token* — this path issues tokens and receives none).
        if (siteKey.length > ConsentEventRequest.MAX_SITE_KEY_LENGTH) {
            throw MalformedSiteKeyException()
        }
        val minted = consentOriginToken.mint(siteKey, boundedOrigin(httpRequest))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.success(ConsentTokenResponse(minted.token, minted.expiresInSeconds)))
    }

    /**
     * The request's `Origin` header, or null when it is absent or implausibly long. A real browser
     * origin is well under [MAX_ORIGIN_HEADER_LENGTH]; dropping an over-long one (rather than signing
     * it) keeps the token small and symmetric with the site-key bound, and only costs the attacker
     * their own origin binding — the resulting unbound token still verifies.
     */
    private fun boundedOrigin(httpRequest: HttpServletRequest): String? =
        httpRequest.getHeader(HttpHeaders.ORIGIN)?.takeIf { it.length <= MAX_ORIGIN_HEADER_LENGTH }

    private companion object {
        /** Generous ceiling for a real `Origin` (scheme + host + optional port is ~253 chars max). */
        const val MAX_ORIGIN_HEADER_LENGTH = 253
    }
}
