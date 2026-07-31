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
    val cors: Cors = Cors(),
    val consent: Consent = Consent(),
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
        // Requests per minute per client IP on public consent ingestion. Set generously:
        // large corporate NATs / carrier CGNAT share one IP across many visitors, and a
        // single visitor emits ~1 event per choice. Edge rate limiting (Cloudflare) absorbs
        // volumetric floods; this is only the coarse per-IP backstop.
        val consentPerMinute: Long = DEFAULT_CONSENT_PER_MINUTE,
    ) {
        companion object {
            const val DEFAULT_AUTH_PER_MINUTE = 10L
            const val DEFAULT_CONSENT_PER_MINUTE = 120L
        }
    }

    /**
     * CORS policy for the public widget endpoints (see [com.complyr.common.CorsConfig]).
     * All fields are optional in `complyr.cors.*`; when unset the defaults below apply —
     * credential-less, all-origins, matching the widget's long-standing policy. Origins are
     * applied as `allowedOriginPatterns`, so `"*"` is valid even though credentials stay off.
     */
    data class Cors(
        val allowedOrigins: List<String> = DEFAULT_ALLOWED_ORIGINS,
        val allowedMethods: List<String> = DEFAULT_ALLOWED_METHODS,
        val allowedHeaders: List<String> = DEFAULT_ALLOWED_HEADERS,
        val allowCredentials: Boolean = false,
        val maxAge: Duration = Duration.ofSeconds(DEFAULT_MAX_AGE_SECONDS),
        val paths: List<String> = DEFAULT_PATHS,
    ) {
        companion object {
            val DEFAULT_ALLOWED_ORIGINS = listOf("*")
            val DEFAULT_ALLOWED_METHODS = listOf("GET", "POST", "OPTIONS")
            val DEFAULT_ALLOWED_HEADERS = listOf("Content-Type")
            const val DEFAULT_MAX_AGE_SECONDS = 3600L
            val DEFAULT_PATHS = listOf("/api/v1/consent", "/api/v1/widget-config/**")
        }
    }

    /**
     * Consent-ingestion tuning. [idempotencyRetention] is how long a claimed dedupe key lives in
     * `consent_idempotency` before the scheduled reaper prunes it (see
     * [com.complyr.consent.ConsentIdempotencyReaper]). It only has to outlive a pending widget
     * retry — the localStorage replay queue drains within days — not the multi-year consent
     * retention. A short window keeps this non-partitioned, DELETE-pruned table small and bounds
     * its bloat (see V5 migration). The prune schedule itself is the raw `complyr.consent.
     * idempotency-prune-cron` property read by `@Scheduled`, not a typed field here.
     */
    data class Consent(
        val idempotencyRetention: Duration = Duration.ofDays(DEFAULT_IDEMPOTENCY_RETENTION_DAYS),
    ) {
        init {
            // A zero/negative window makes cutoff >= now, so the reaper would delete still-active,
            // in-flight keys and silently disable dedupe (fails open). Refuse the misconfig at startup.
            require(!idempotencyRetention.isZero && !idempotencyRetention.isNegative) {
                "complyr.consent.idempotency-retention must be a positive duration (was $idempotencyRetention)"
            }
        }

        companion object {
            const val DEFAULT_IDEMPOTENCY_RETENTION_DAYS = 14L
        }
    }
}
