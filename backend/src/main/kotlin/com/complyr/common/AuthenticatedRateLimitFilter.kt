package com.complyr.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Post-authentication, per-user rate limiting for the authenticated REST API.
 *
 * The pre-auth [RateLimitFilter] is keyed by client IP and deliberately exempts authenticated paths
 * (a NATed office or CGNAT shares one IP, so IP-keying would throttle a whole tenant out of normal
 * session traffic). That leaves every authed endpoint under `/api/v1` with no per-account ceiling, so
 * a single authenticated account can loop an endpoint and burn a shared downstream budget. This filter
 * closes that gap, keyed on the JWT subject (user id):
 *
 *  - **BILLING tier (tight):** every call under `/api/v1/billing` is a live Stripe API round-trip
 *    (checkout and portal sessions). An authenticated loop would exhaust the shared Stripe rate budget
 *    and degrade billing for all tenants.
 *  - **VERIFY tier (tightest):** `POST /api/v1/sites/{id}/verify` is the only authed endpoint that makes
 *    the `api` container dial a *customer-supplied* host synchronously on a Tomcat request thread
 *    (ADR-17). Two distinct budgets need bounding: the request threads it occupies for up to the
 *    verification budget each, and the outbound requests it lets an authenticated account aim at third
 *    parties. A handful per minute is far above the handful per *lifetime* a real activation takes.
 *  - **GENERAL tier (generous backstop):** all other authed `/api/v1` calls — e.g.
 *    `POST /api/v1/sites` (each synchronously enqueues a Chromium scan) or consent-log reads (keyset
 *    queries fanning across monthly partitions). A cap normal dashboard use never reaches, but which
 *    bounds amplification abuse.
 *
 * Registered at [Ordered.LOWEST_PRECEDENCE] so it executes downstream of Spring Security's filter
 * chain — after the bearer token has been resolved into the `SecurityContext`. Requests with no
 * authenticated JWT are passed straight through: unauthenticated traffic is the IP filter's and the
 * security layer's concern, not this one's. The `/api/v1/billing/webhook` endpoint is unauthenticated
 * (Stripe cannot present a JWT) and is excluded from tier matching. User ids are bucket keys only —
 * never logged, never persisted (CLAUDE.md #4). Edge rate limiting (Cloudflare) layers on top.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AuthenticatedRateLimitFilter(
    private val properties: ComplyrProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val buckets = RateLimitBuckets(properties.rateLimit.maxTrackedKeys)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        HttpMethod.OPTIONS.matches(request.method) || tierFor(RequestPaths.tierPath(request)) == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val tier = tierFor(RequestPaths.tierPath(request)) ?: return filterChain.doFilter(request, response)
        // No authenticated principal (public endpoint, or an absent/expired token): not ours to
        // throttle — let it flow on so the security layer decides the outcome.
        val userId = authenticatedUserId() ?: return filterChain.doFilter(request, response)

        val key = "${tier.name}|$userId"
        if (buckets.tryConsume(key, capacityFor(tier))) {
            filterChain.doFilter(request, response)
        } else {
            writeRateLimited(response)
        }
    }

    /** The authenticated user's JWT subject, or null when there is no authenticated JWT principal. */
    private fun authenticatedUserId(): String? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        if (!authentication.isAuthenticated) return null
        return (authentication.principal as? Jwt)?.subject?.takeIf { it.isNotBlank() }
    }

    private fun tierFor(uri: String): Tier? =
        when {
            // Unauthenticated by construction (Stripe cannot send a JWT) — no principal to key on.
            uri == BILLING_WEBHOOK_PATH -> null
            uri == BILLING_PATH || uri.startsWith("$BILLING_PATH/") -> Tier.BILLING
            // Ordered before the GENERAL fallthrough, which would otherwise swallow it. Matching is
            // method-blind here as everywhere in this filter, which is why the suffix must be exact:
            // `GET /api/v1/sites/{id}/scans` is polled every 3s by the dashboard and must stay GENERAL.
            uri.startsWith(SITES_PATH_PREFIX) && uri.endsWith(VERIFY_SUFFIX) -> Tier.VERIFY
            uri.startsWith(API_PREFIX) -> Tier.GENERAL
            else -> null
        }

    private fun capacityFor(tier: Tier): Long =
        when (tier) {
            Tier.BILLING -> properties.rateLimit.authBillingPerMinute
            Tier.VERIFY -> properties.rateLimit.authVerifyPerMinute
            Tier.GENERAL -> properties.rateLimit.authGeneralPerMinute
        }

    private fun writeRateLimited(response: HttpServletResponse) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                ApiResponse.error(code = "RATE_LIMITED", message = "Too many requests, retry later"),
            ),
        )
    }

    private enum class Tier { BILLING, VERIFY, GENERAL }

    companion object {
        const val API_PREFIX = "/api/v1/"
        const val BILLING_PATH = "/api/v1/billing"
        const val BILLING_WEBHOOK_PATH = "/api/v1/billing/webhook"
        const val SITES_PATH_PREFIX = "/api/v1/sites/"
        const val VERIFY_SUFFIX = "/verify"

        // Buckets refill over a 1-minute window, so a drained caller can retry after at most 60s.
        private const val RETRY_AFTER_SECONDS = "60"
    }
}
