package com.complyr.common

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-client-IP rate limiting (Bucket4j) for the unauthenticated auth endpoints.
 * Authenticated endpoints (`/me`) and logout are exempt so a NATed office is not throttled
 * out of normal session traffic. `remoteAddr` is the real client IP behind Caddy thanks to
 * `server.forward-headers-strategy: native` (see application.yml for the trust model).
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
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI !in RATE_LIMITED_PATHS

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // Crude memory bound: dropping all buckets under churn only relaxes limits briefly.
        if (buckets.size > MAX_TRACKED_CLIENTS) buckets.clear()

        val bucket = buckets.computeIfAbsent(request.remoteAddr) { newBucket() }
        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response)
        } else {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(
                objectMapper.writeValueAsString(
                    ApiResponse.error(code = "RATE_LIMITED", message = "Too many requests, retry later"),
                ),
            )
        }
    }

    private fun newBucket(): Bucket {
        val perMinute = properties.rateLimit.authPerMinute
        return Bucket
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(perMinute)
                    .refillGreedy(perMinute, Duration.ofMinutes(1))
                    .build(),
            ).build()
    }

    companion object {
        val RATE_LIMITED_PATHS =
            setOf(
                "/api/v1/auth/signup",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/verify-email",
                "/api/v1/auth/resend-verification",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
            )
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
