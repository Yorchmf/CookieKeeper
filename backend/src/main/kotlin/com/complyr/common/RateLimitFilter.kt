package com.complyr.common

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-client-IP rate limiting (Bucket4j) for the unauthenticated public endpoints.
 * Two tiers with independent limits: the auth endpoints (tight) and public consent ingestion
 * (generous — see [ComplyrProperties.RateLimit.consentPerMinute]). Authenticated endpoints and
 * logout are exempt so a NATed office is not throttled out of normal session traffic.
 * CORS preflight (OPTIONS) is never counted. `remoteAddr` is the real client IP behind Caddy
 * thanks to `server.forward-headers-strategy: native` (see application.yml for the trust model).
 *
 * IPs are used only as in-memory bucket keys — never logged, never persisted
 * (CLAUDE.md constraint #4). Edge rate limiting (Cloudflare) layers on top of this.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter(
    private val properties: ComplyrProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val clients = ConcurrentHashMap<String, TrackedBucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        HttpMethod.OPTIONS.matches(request.method) || tierFor(normalizedPath(request)) == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val tier = tierFor(normalizedPath(request)) ?: return filterChain.doFilter(request, response)
        val capacity = capacityFor(tier)
        evictIdleWhenOverCap()

        val key = "${tier.name}|${request.remoteAddr}"
        val tracked = clients.computeIfAbsent(key) { TrackedBucket(newBucket(capacity), capacity) }
        if (tracked.bucket.tryConsume(1)) {
            filterChain.doFilter(request, response)
        } else {
            writeRateLimited(response)
        }
    }

    /**
     * Memory bound: when tracking too many clients, evict only idle buckets — those refilled
     * back to their own full capacity, i.e. clients not currently being throttled. Never drop a
     * bucket mid-throttle, so an attacker spraying many source IPs cannot push the map over the
     * cap to reset a targeted client's (or their own) limit. An evicted idle bucket is recreated
     * — starting full — on that client's next request, which is harmless.
     */
    private fun evictIdleWhenOverCap() {
        if (clients.size <= MAX_TRACKED_CLIENTS) return
        // First choice: drop only idle buckets (refilled to full) — never resets a client
        // mid-throttle, so a source-IP spray can't push the map over cap to reset a target.
        clients.values.removeIf { it.bucket.availableTokens >= it.capacity }
        // Absolute bound: a distributed spray can keep every bucket partially drained so none
        // are idle. Still cap memory by dropping arbitrary entries. At >cap distinct IPs/min
        // this is an attack the edge (Cloudflare) is the primary control for, and a reset
        // bucket only grants one client a fresh (already generous) allowance — benign.
        if (clients.size > MAX_TRACKED_CLIENTS) {
            val iterator = clients.keys.iterator()
            while (clients.size > MAX_TRACKED_CLIENTS && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    /** Strip matrix/path params so `/api/v1/consent;x=1` cannot slip past tier matching. */
    private fun normalizedPath(request: HttpServletRequest): String = request.requestURI.substringBefore(';')

    private fun tierFor(uri: String): Tier? =
        when {
            uri in AUTH_PATHS -> Tier.AUTH
            uri == CONSENT_PATH -> Tier.CONSENT
            else -> null
        }

    private fun capacityFor(tier: Tier): Long =
        when (tier) {
            Tier.AUTH -> properties.rateLimit.authPerMinute
            Tier.CONSENT -> properties.rateLimit.consentPerMinute
        }

    private fun writeRateLimited(response: HttpServletResponse) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            objectMapper.writeValueAsString(
                ApiResponse.error(code = "RATE_LIMITED", message = "Too many requests, retry later"),
            ),
        )
    }

    private fun newBucket(perMinute: Long): Bucket =
        Bucket
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(perMinute)
                    .refillGreedy(perMinute, Duration.ofMinutes(1))
                    .build(),
            ).build()

    private enum class Tier { AUTH, CONSENT }

    private class TrackedBucket(
        val bucket: Bucket,
        val capacity: Long,
    )

    companion object {
        val AUTH_PATHS =
            setOf(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
            )
        const val CONSENT_PATH = "/api/v1/consent"
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
