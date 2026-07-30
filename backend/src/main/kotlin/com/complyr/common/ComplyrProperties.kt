package com.complyr.common

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Typed, immutable view of the `complyr.*` configuration tree (application.yml / env vars).
 */
@ConfigurationProperties("complyr")
data class ComplyrProperties(
    val auth: Auth,
    val rateLimit: RateLimit = RateLimit(),
    val appBaseUrl: String,
    val cdnBaseUrl: String,
    val mailFrom: String,
) {
    data class Auth(
        val jwtSecret: String,
        val accessTokenTtl: Duration,
        val refreshTokenTtl: Duration,
        val verificationTokenTtl: Duration,
        val resetTokenTtl: Duration,
        // Reuse tolerance for the immediately-preceding refresh token (parallel-refresh races).
        val refreshReuseGrace: Duration = Duration.ofSeconds(DEFAULT_REFRESH_REUSE_GRACE_SECONDS),
    ) {
        init {
            require(jwtSecret.toByteArray(Charsets.UTF_8).size >= MIN_JWT_SECRET_BYTES) {
                "complyr.auth.jwt-secret must be at least $MIN_JWT_SECRET_BYTES bytes (HS256 key material)"
            }
        }

        companion object {
            const val MIN_JWT_SECRET_BYTES = 32
            const val DEFAULT_REFRESH_REUSE_GRACE_SECONDS = 10L
        }
    }

    data class RateLimit(
        // Requests per minute per client IP on the auth endpoints (raised in tests).
        val authPerMinute: Long = DEFAULT_AUTH_PER_MINUTE,
    ) {
        companion object {
            const val DEFAULT_AUTH_PER_MINUTE = 10L
        }
    }
}
