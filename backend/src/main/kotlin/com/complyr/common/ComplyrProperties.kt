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
    val scan: Scan = Scan(),
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
        // Requests per minute per client IP on the anonymous free-scan endpoint. Tighter than
        // consent: a human scans their own domain a handful of times, and every request can spawn a
        // Chromium crawl, so this tier is the first-line brake on crawl-compute abuse. The per-IP
        // concurrency cap (complyr.scan.max-concurrent-scans-per-ip) and edge limiting back it up.
        val publicScanPerMinute: Long = DEFAULT_PUBLIC_SCAN_PER_MINUTE,
    ) {
        companion object {
            const val DEFAULT_AUTH_PER_MINUTE = 10L
            const val DEFAULT_CONSENT_PER_MINUTE = 120L
            const val DEFAULT_PUBLIC_SCAN_PER_MINUTE = 10L
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
     *
     * [idempotencyPruneBatchSize] caps how many rows the reaper deletes per transaction so a large
     * backlog (e.g. the reaper disabled for a stretch, or a widened retention window) drains in
     * bounded chunks instead of one long-running DELETE that pins the vacuum horizon and bursts
     * dead tuples — see [com.complyr.consent.ConsentIdempotencyReaper].
     */
    data class Consent(
        val idempotencyRetention: Duration = Duration.ofDays(DEFAULT_IDEMPOTENCY_RETENTION_DAYS),
        val idempotencyPruneBatchSize: Int = DEFAULT_IDEMPOTENCY_PRUNE_BATCH_SIZE,
    ) {
        init {
            // A zero/negative window makes cutoff >= now, so the reaper would delete still-active,
            // in-flight keys and silently disable dedupe (fails open). Refuse the misconfig at startup.
            require(!idempotencyRetention.isZero && !idempotencyRetention.isNegative) {
                "complyr.consent.idempotency-retention must be a positive duration (was $idempotencyRetention)"
            }
            // A non-positive batch size would make the reaper delete nothing and loop forever;
            // refuse it at startup rather than silently disabling the prune.
            require(idempotencyPruneBatchSize > 0) {
                "complyr.consent.idempotency-prune-batch-size must be positive (was $idempotencyPruneBatchSize)"
            }
        }

        companion object {
            const val DEFAULT_IDEMPOTENCY_RETENTION_DAYS = 14L

            // Rows per prune transaction. Large enough that steady-state churn drains in one batch,
            // small enough that a backlog stays chunked into short, vacuum-friendly transactions.
            const val DEFAULT_IDEMPOTENCY_PRUNE_BATCH_SIZE = 10_000
        }
    }

    /**
     * Scan queue tuning (see [com.complyr.scan.ScanQueue] / [com.complyr.scan.ScanWorker]).
     *
     * [visibilityTimeout] is how long a claimed job is hidden from other workers before it is treated
     * as crashed and redelivered — it MUST exceed the worst-case crawl time (§4.4 caps a job at 10min)
     * or a slow-but-healthy crawl would be double-claimed. [maxAttempts] bounds retries before a job
     * is dead-lettered; [retryBackoff] is multiplied by the attempt number for a linear backoff on
     * requeue. [maxJobsPerPoll] caps how many jobs one worker tick drains so a backlog can't make a
     * single tick run unbounded. [maxPages] is the per-scan page cap the crawler will honor (slice 2).
     *
     * The poll interval itself is read directly by `@Scheduled` from `complyr.scan.poll-interval-millis`
     * (a `fixedDelayString` needs a literal/placeholder, not a typed field), mirroring how the consent
     * reaper's cron is read directly rather than typed here.
     */
    data class Scan(
        val visibilityTimeout: Duration = Duration.ofMinutes(DEFAULT_VISIBILITY_TIMEOUT_MINUTES),
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val retryBackoff: Duration = Duration.ofMinutes(DEFAULT_RETRY_BACKOFF_MINUTES),
        val maxJobsPerPoll: Int = DEFAULT_MAX_JOBS_PER_POLL,
        val maxPages: Int = DEFAULT_MAX_PAGES,
        // Per-page navigation cap (§4.4) — one slow page can't stall a crawl.
        val pageTimeout: Duration = Duration.ofSeconds(DEFAULT_PAGE_TIMEOUT_SECONDS),
        // Whole-job wall-clock cap (§4.4) — the crawler stops opening pages past this budget.
        val jobTimeout: Duration = Duration.ofMinutes(DEFAULT_JOB_TIMEOUT_MINUTES),
        // Abuse bounds on the attacker-influenced cookie set persisted per scan (§4.4): a hostile but
        // verified site can drop many cookies with long, arbitrary names into an unbounded `text`
        // column. [maxCookies] caps the rows one scan records; [maxCookieNameLength] truncates names.
        val maxCookies: Int = DEFAULT_MAX_COOKIES,
        val maxCookieNameLength: Int = DEFAULT_MAX_COOKIE_NAME_LENGTH,
        // Abuse cap on the anonymous funnel: how many scans one requester (by rotating-salt ip_hash)
        // may have in flight (queued or running) at once. Bounds a single requester's share of the
        // shared crawl pool beyond the per-minute rate limit, since a burst under the rate limit
        // could otherwise stack many concurrent Chromium crawls. Enforced only when an ip_hash is
        // available; see [com.complyr.scan.PublicScanService].
        val maxConcurrentScansPerIp: Int = DEFAULT_MAX_CONCURRENT_SCANS_PER_IP,
    ) {
        init {
            require(!visibilityTimeout.isZero && !visibilityTimeout.isNegative) {
                "complyr.scan.visibility-timeout must be a positive duration (was $visibilityTimeout)"
            }
            require(!retryBackoff.isNegative) {
                "complyr.scan.retry-backoff must not be negative (was $retryBackoff)"
            }
            require(maxAttempts > 0) { "complyr.scan.max-attempts must be positive (was $maxAttempts)" }
            require(maxJobsPerPoll > 0) { "complyr.scan.max-jobs-per-poll must be positive (was $maxJobsPerPoll)" }
            require(maxPages > 0) { "complyr.scan.max-pages must be positive (was $maxPages)" }
            require(!pageTimeout.isZero && !pageTimeout.isNegative) {
                "complyr.scan.page-timeout must be a positive duration (was $pageTimeout)"
            }
            require(!jobTimeout.isZero && !jobTimeout.isNegative) {
                "complyr.scan.job-timeout must be a positive duration (was $jobTimeout)"
            }
            require(maxCookies > 0) { "complyr.scan.max-cookies must be positive (was $maxCookies)" }
            require(maxCookieNameLength > 0) {
                "complyr.scan.max-cookie-name-length must be positive (was $maxCookieNameLength)"
            }
            require(maxConcurrentScansPerIp > 0) {
                "complyr.scan.max-concurrent-scans-per-ip must be positive (was $maxConcurrentScansPerIp)"
            }
            // The queue redelivers a job once its visibility lease lapses; if a healthy crawl could run
            // longer than that lease it would be double-claimed (ADR-4 invariant). The job budget is
            // only checked *between* pages, so the last page can start at ~jobTimeout and run a further
            // pageTimeout — include that tail so a slow-but-live crawl always finishes before redelivery.
            require(jobTimeout + pageTimeout <= visibilityTimeout) {
                "complyr.scan.job-timeout ($jobTimeout) + page-timeout ($pageTimeout) must not exceed " +
                    "visibility-timeout ($visibilityTimeout)"
            }
        }

        companion object {
            const val DEFAULT_VISIBILITY_TIMEOUT_MINUTES = 15L
            const val DEFAULT_MAX_ATTEMPTS = 3
            const val DEFAULT_RETRY_BACKOFF_MINUTES = 1L
            const val DEFAULT_MAX_JOBS_PER_POLL = 50
            const val DEFAULT_MAX_PAGES = 10
            const val DEFAULT_PAGE_TIMEOUT_SECONDS = 60L
            const val DEFAULT_JOB_TIMEOUT_MINUTES = 10L

            // Generous enough for ad-heavy but legitimate sites; still bounds a hostile flood. A
            // realistic pre-consent cookie name is short, so 256 chars only ever clips junk.
            const val DEFAULT_MAX_COOKIES = 500
            const val DEFAULT_MAX_COOKIE_NAME_LENGTH = 256

            // A human scanning a couple of domains never needs more than a few in flight; a bot
            // stacking crawls does. Low enough to bound abuse, high enough not to block real retries.
            const val DEFAULT_MAX_CONCURRENT_SCANS_PER_IP = 3
        }
    }
}
