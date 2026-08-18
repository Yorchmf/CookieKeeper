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
    val impression: Impression = Impression(),
    val scan: Scan = Scan(),
    val verification: Verification = Verification(),
    val billing: Billing = Billing(),
    val mail: Mail = Mail(),
    val observability: Observability = Observability(),
    val appBaseUrl: String,
    val cdnBaseUrl: String,
    val mailFrom: String,
    // Destination for in-app support contact-form messages (com.complyr.support). Unlike [mailFrom] this
    // carries a safe default so the 21 unit tests that build ComplyrProperties by hand need not each name
    // it; application.yml overrides it from ${SUPPORT_INBOX}. It is our own inbox (not customer PII); the
    // submitting customer's address rides the email's Reply-To, never this field.
    val supportInbox: String = DEFAULT_SUPPORT_INBOX,
) {
    companion object {
        const val DEFAULT_SUPPORT_INBOX = "support@complyr.eu"
    }

    data class Auth(
        val jwtSecret: String,
        val accessTokenTtl: Duration,
        val refreshTokenTtl: Duration,
        val verificationTokenTtl: Duration,
        val resetTokenTtl: Duration,
        // TTL for the email-change confirmation token (V24). Defaulted (rather than required like the two
        // above) so existing constructions need no change; mirrors verification's 24h window — long enough
        // for someone to click the link from the new mailbox that day, short enough to bound how long a
        // parked pending change stays confirmable.
        val emailChangeTokenTtl: Duration = Duration.ofHours(DEFAULT_EMAIL_CHANGE_TOKEN_TTL_HOURS),
        // Reuse tolerance for the immediately-preceding refresh token (parallel-refresh races).
        val refreshReuseGrace: Duration = Duration.ofSeconds(DEFAULT_REFRESH_REUSE_GRACE_SECONDS),
        // Consecutive failed logins that lock an account, and for how long. The per-IP auth rate limit
        // bounds one source; this per-account backstop bounds a botnet spraying one email from many IPs
        // (see [com.complyr.auth.LoginAttemptService]). The lock is TEMPORARY (auto-expiring) rather than
        // permanent, and a successful login clears the counter — but note the tradeoff inherent to any
        // account-lockout scheme: an attacker who knows a victim's email can re-trigger the lock every
        // window to keep that user out. Accepted for MVP as a bounded, self-clearing DoS; a non-lockout
        // mitigation (self-service unlock / step-up challenge after N failures) is a tracked follow-up.
        val maxFailedLoginAttempts: Int = DEFAULT_MAX_FAILED_LOGIN_ATTEMPTS,
        val loginLockoutDuration: Duration = Duration.ofMinutes(DEFAULT_LOGIN_LOCKOUT_MINUTES),
    ) {
        init {
            require(jwtSecret.toByteArray(Charsets.UTF_8).size >= MIN_JWT_SECRET_BYTES) {
                "complyr.auth.jwt-secret must be at least $MIN_JWT_SECRET_BYTES bytes (HS256 key material)"
            }
            // A non-positive threshold would lock every account on its first (or zeroth) failed login,
            // locking out all legitimate users; refuse the misconfig at startup.
            require(maxFailedLoginAttempts > 0) {
                "complyr.auth.max-failed-login-attempts must be positive (was $maxFailedLoginAttempts)"
            }
            // A zero/negative window would mint an already-elapsed lock, disabling the lockout entirely.
            require(!loginLockoutDuration.isZero && !loginLockoutDuration.isNegative) {
                "complyr.auth.login-lockout-duration must be a positive duration (was $loginLockoutDuration)"
            }
            // A zero/negative TTL would mint an already-expired confirmation link, so no email change could
            // ever complete. Refuse the misconfig at startup.
            require(!emailChangeTokenTtl.isZero && !emailChangeTokenTtl.isNegative) {
                "complyr.auth.email-change-token-ttl must be a positive duration (was $emailChangeTokenTtl)"
            }
        }

        companion object {
            const val MIN_JWT_SECRET_BYTES = 32
            const val DEFAULT_REFRESH_REUSE_GRACE_SECONDS = 10L
            const val DEFAULT_EMAIL_CHANGE_TOKEN_TTL_HOURS = 24L

            // Generous enough that a real user fat-fingering a password a few times is never locked in
            // normal use, tight enough to make online guessing against one account infeasible.
            const val DEFAULT_MAX_FAILED_LOGIN_ATTEMPTS = 10
            const val DEFAULT_LOGIN_LOCKOUT_MINUTES = 15L
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
        // Requests per minute per client IP on the hosted cookie-policy read. Generous: the response
        // is a cacheable GET fronted by Cloudflare, addressed by an unguessable public id, and a real
        // visitor loads it once. This is only the coarse per-IP backstop against a single id being
        // hammered past the edge cache; enumeration is infeasible (random UUID public id).
        val publicPolicyPerMinute: Long = DEFAULT_PUBLIC_POLICY_PER_MINUTE,
        // Requests per minute per client IP on the public banner-impression beacon (Track 4 Slice D).
        // Sized like consent, and for the same reason: shared corporate NAT / CGNAT egress puts many
        // real visitors behind one IP, and the widget fires exactly one beacon per page-load. The beacon
        // is a single UPSERT with no audit write, so a generous per-IP backstop is right here — edge rate
        // limiting (Cloudflare) absorbs volumetric floods. (Not strictly "cheaper" than a consent post
        // under load: impressions for one busy site all contend on the same (site, day) row where consent
        // inserts distinct rows — fine at MVP scale; shard the counter if a single site ever outgrows it.)
        val impressionPerMinute: Long = DEFAULT_IMPRESSION_PER_MINUTE,
        // Requests per minute per authenticated user on `/api/v1/billing/**` (post-auth, keyed by the
        // JWT subject — see [com.complyr.common.AuthenticatedRateLimitFilter]). Tight: every billing
        // call is a live Stripe API round-trip, so one account looping checkout/portal could burn the
        // shared Stripe rate budget for all tenants. Normal use is a handful of clicks per session.
        val authBillingPerMinute: Long = DEFAULT_AUTH_BILLING_PER_MINUTE,
        // Requests per minute per authenticated user on `/api/v1/account/**` — the customer's own GDPR
        // surface (ADR-20). Tight for two independent reasons: `POST /account/delete` re-verifies the
        // account password, so a generous tier turns it into an online password oracle and a bcrypt
        // CPU-exhaustion primitive on a 2-vCPU box; `GET /account/export.json` assembles the whole
        // account in memory at several queries per site. The per-account lockout (LoginAttemptService)
        // is the primary control on the first; this bounds both. Real use is a handful of calls ever.
        val authAccountPerMinute: Long = DEFAULT_AUTH_ACCOUNT_PER_MINUTE,
        // Requests per minute per authenticated user on `POST /api/v1/sites/{id}/verify`. Tightest of
        // the three: it is the only authed endpoint that dials a *customer-supplied* host synchronously
        // on a Tomcat request thread (ADR-17), so it bounds both request-thread occupancy and the
        // outbound requests an account can aim at third parties. A real activation takes a handful of
        // attempts in total, not per minute, and a verified site short-circuits before any I/O.
        val authVerifyPerMinute: Long = DEFAULT_AUTH_VERIFY_PER_MINUTE,
        // Requests per minute per authenticated user on the Business-plan bulk-export downloads —
        // `GET /api/v1/sites/{id}/analytics/export.csv` and `.../evidence-pack.zip`. Tight because
        // each is far heavier than a typical authed GET: the evidence pack streams the trailing 30
        // days of consent audit evidence *and* every published policy-language HTML *and* the latest
        // scan summary per request. Neither endpoint is polled — a real customer downloads a pack a
        // handful of times ever — so leaving them on the generous GENERAL tier let one account hammer
        // the heaviest assembly on the box. The server-side entitlement gate (Business only) narrows
        // who can reach it at all; this bounds how hard they can. Both endpoints share this one bucket,
        // so it is set at VERIFY parity (5/min): the pack is the DoS concern the tier exists for, it
        // pins a Tomcat thread and streams even longer than a verify probe, and the cheap CSV tolerates
        // the same tight cap without complaint. Rate only — thread-pool sizing bounds concurrency.
        val authExportPerMinute: Long = DEFAULT_AUTH_EXPORT_PER_MINUTE,
        // Requests per minute per authenticated user on `POST /api/v1/support/contact` — the in-app
        // contact form. Tight because every accepted call composes and sends an email to our support
        // inbox: an authenticated loop would flood the inbox (and burn the shared mail-provider budget)
        // even though the form is a handful-of-times-ever action for a real customer. Reply-To is the
        // caller's own verified account address, so this is spam-to-ourselves protection, not an open
        // relay — but the cap keeps a compromised or abusive session from turning the form into one.
        val authContactPerMinute: Long = DEFAULT_AUTH_CONTACT_PER_MINUTE,
        // Requests per minute per authenticated user on all other authed `/api/v1/**` endpoints. A
        // generous backstop a real dashboard session never reaches, bounding amplification abuse
        // (e.g. `POST /api/v1/sites` enqueues a Chromium crawl; consent-log reads fan across monthly
        // partitions). The per-user advisory locks (site-cap, webhook) and edge limiting (Cloudflare)
        // remain the primary controls; this is the coarse per-account ceiling behind them.
        val authGeneralPerMinute: Long = DEFAULT_AUTH_GENERAL_PER_MINUTE,
        // Hard cap on distinct keys each in-memory bucket registry tracks before it evicts (idle-first)
        // to bound memory — see [com.complyr.common.RateLimitBuckets]. Sized with headroom for the
        // authenticated registry's fan-out: it keys by `tier|userId`, so an active tenant can hold up
        // to 6 keys (billing + account + verify + export + contact + general). The default covers well
        // past MVP scale; raise it before the distinct tracked-key count (up to ~6 per fully-active
        // tenant) approaches half this value, or steady-state traffic keeps the map at cap and turns
        // eviction from a rare safety valve into a per-new-key cost.
        val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    ) {
        init {
            require(authBillingPerMinute > 0) { "complyr.rate-limit.auth-billing-per-minute must be > 0" }
            require(authAccountPerMinute > 0) { "complyr.rate-limit.auth-account-per-minute must be > 0" }
            require(authVerifyPerMinute > 0) { "complyr.rate-limit.auth-verify-per-minute must be > 0" }
            require(authExportPerMinute > 0) { "complyr.rate-limit.auth-export-per-minute must be > 0" }
            require(authContactPerMinute > 0) { "complyr.rate-limit.auth-contact-per-minute must be > 0" }
            require(authGeneralPerMinute > 0) { "complyr.rate-limit.auth-general-per-minute must be > 0" }
            require(maxTrackedKeys > 0) { "complyr.rate-limit.max-tracked-keys must be > 0" }
        }

        companion object {
            const val DEFAULT_AUTH_PER_MINUTE = 10L
            const val DEFAULT_CONSENT_PER_MINUTE = 120L
            const val DEFAULT_IMPRESSION_PER_MINUTE = 120L
            const val DEFAULT_PUBLIC_SCAN_PER_MINUTE = 10L
            const val DEFAULT_PUBLIC_POLICY_PER_MINUTE = 120L
            const val DEFAULT_AUTH_BILLING_PER_MINUTE = 20L
            const val DEFAULT_AUTH_ACCOUNT_PER_MINUTE = 10L
            const val DEFAULT_AUTH_VERIFY_PER_MINUTE = 5L
            const val DEFAULT_AUTH_EXPORT_PER_MINUTE = 5L
            const val DEFAULT_AUTH_CONTACT_PER_MINUTE = 5L
            const val DEFAULT_AUTH_GENERAL_PER_MINUTE = 300L
            const val DEFAULT_MAX_TRACKED_KEYS = 50_000
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
            val DEFAULT_PATHS =
                listOf(
                    "/api/v1/consent",
                    // The banner-impression beacon (Track 4 Slice D) — fired cross-origin from the
                    // customer's page exactly like the consent post, so it needs the same CORS grant.
                    "/api/v1/impression",
                    "/api/v1/consent-token/**",
                    "/api/v1/widget-config/**",
                    // The widget's own config URL (ADR-19). Cross-origin by construction: it is
                    // fetched from the customer's page. CORS is owned here, not in Caddy, so the
                    // proxied response carries exactly one Access-Control-Allow-Origin header.
                    "/cfg/**",
                )
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
     *
     * [originTokenSecret] / [originTokenTtl] configure the stateless HMAC consent-origin token (see
     * [com.complyr.consent.ConsentOriginToken]). The secret is CONFIGURED, not random-per-process,
     * so a token minted just before a deploy still verifies afterwards — a restart must never reject
     * a legitimate in-flight consent event (that would lose audit evidence). It is bound in from
     * `${'$'}{CONSENT_ORIGIN_TOKEN_SECRET}` with no default in application.yml (dev/prd fail fast if
     * unset); the empty default below only exists so the no-arg `Consent()` used by unrelated unit
     * tests still constructs — the [com.complyr.consent.ConsentOriginToken] bean rejects a secret
     * shorter than 32 bytes at startup. [originTokenTtl] is kept short (a replayed token dies with it)
     * but comfortably above client→server latency; the widget self-censors tokens older than ~90s.
     */
    data class Consent(
        val idempotencyRetention: Duration = Duration.ofDays(DEFAULT_IDEMPOTENCY_RETENTION_DAYS),
        val idempotencyPruneBatchSize: Int = DEFAULT_IDEMPOTENCY_PRUNE_BATCH_SIZE,
        val originTokenSecret: String = "",
        val originTokenTtl: Duration = Duration.ofMinutes(DEFAULT_ORIGIN_TOKEN_TTL_MINUTES),
        val partitionLookaheadMonths: Int = DEFAULT_PARTITION_LOOKAHEAD_MONTHS,
        val retentionMonths: Int = DEFAULT_RETENTION_MONTHS,
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
            // At least the current + next month must always exist ahead of the write path, or a
            // month-boundary crossing between provisioning runs lands rows in the un-reclaimable
            // DEFAULT partition (GDPR storage-limitation risk, see ConsentEventPartitionProvisioner).
            require(partitionLookaheadMonths >= 1) {
                "complyr.consent.partition-lookahead-months must be at least 1 (was $partitionLookaheadMonths)"
            }
            // Consent evidence is dropped a whole partition at a time and irreversibly (ADR-16), and the
            // window is TENANT-BLIND, so it MUST cover the LONGEST plan retention (Pro/Business, 3 yr =
            // 36 mo — billing.Plan.RETENTION_YEARS_PAID), NOT the shortest. Anything below that would let
            // the reaper irreversibly drop consent evidence a paying customer is still entitled to on its
            // next run. The floor is the longest plan window, refusing that misconfig (and any fat-finger
            // like 0/1) at startup — see ADR-16 and [ConsentEventPartitionReaper].
            require(retentionMonths >= MIN_RETENTION_MONTHS) {
                "complyr.consent.retention-months must be at least $MIN_RETENTION_MONTHS " +
                    "(the longest plan retention; was $retentionMonths)"
            }
            // A zero/negative TTL would mint already-expired tokens, rejecting every token-bearing
            // (i.e. every current-widget) consent post. Refuse it at startup. Secret length is
            // validated by the ConsentOriginToken bean, not here, so the empty test default passes.
            require(!originTokenTtl.isZero && !originTokenTtl.isNegative) {
                "complyr.consent.origin-token-ttl must be a positive duration (was $originTokenTtl)"
            }
        }

        companion object {
            const val DEFAULT_IDEMPOTENCY_RETENTION_DAYS = 14L

            // Rows per prune transaction. Large enough that steady-state churn drains in one batch,
            // small enough that a backlog stays chunked into short, vacuum-friendly transactions.
            const val DEFAULT_IDEMPOTENCY_PRUNE_BATCH_SIZE = 10_000

            // Short enough that a captured payload's token dies quickly, long enough to survive
            // normal client→server latency and modest clock skew (widget attaches only if <~90s old).
            const val DEFAULT_ORIGIN_TOKEN_TTL_MINUTES = 2L

            // Months of consent_events partitions to pre-create beyond the current month. 3 gives a
            // wide buffer: even if the nightly provisioner is down for weeks, rows still find a home
            // and never fall into the un-reclaimable DEFAULT partition. See
            // [com.complyr.consent.ConsentEventPartitionProvisioner].
            const val DEFAULT_PARTITION_LOOKAHEAD_MONTHS = 3

            // Consent-log retention window in months (ADR-16). Default 36 = 3 years, matching the
            // longest plan retention (Pro/Business) and the §5 documented default. The retention job
            // ([com.complyr.consent.ConsentEventPartitionReaper]) drops any monthly partition older
            // than this. Tenant-blind: it must stay >= the longest plan window or a longer-retention
            // customer loses evidence.
            const val DEFAULT_RETENTION_MONTHS = 36

            // Absolute floor for [retentionMonths] — the LONGEST plan retention (Pro/Business, 3 yr).
            // The window is tenant-blind and DROP is irreversible, so it must never fall below the longest
            // entitlement or a paying customer loses evidence they are entitled to. This mirrors
            // billing.Plan.RETENTION_YEARS_PAID (3 yr): the config-binding layer intentionally does not
            // depend on the billing `Plan` type (see [Billing.PriceIds]), so the value is duplicated here
            // and kept honest by a drift guard in ComplyrPropertiesTest.
            const val MIN_RETENTION_MONTHS = 36
        }
    }

    /**
     * Banner-impression counter tuning (Track 4 Slice D; see [com.complyr.analytics.BannerImpressionReaper]).
     *
     * [retention] is how long a per-(site, day) counter row survives before the scheduled reaper prunes
     * it. Impressions are read over the dashboard's analytics windows AND their period-over-period *prior*
     * window, which reaches back up to twice the widest preset — the 90-day view compares against days
     * 90–180 back. So this must comfortably exceed 2× the widest window (not just the window itself), or the
     * prior-window impression / interaction-rate deltas silently vanish once their baseline days are pruned
     * — but still NOT the multi-year consent retention. Unlike the consent log this is a disposable
     * aggregate with no personal data and no audit obligation, so pruning it early loses nothing we must
     * keep. The prune schedule itself is the raw `complyr.impression.prune-cron` property read by
     * `@Scheduled`, not a typed field here.
     *
     * [pruneBatchSize] caps how many counter rows the reaper deletes per transaction, so a backlog drains
     * in bounded, vacuum-friendly chunks instead of one long DELETE — mirrors the consent-idempotency
     * reaper's batching. The grain is one row per (site, day), so even a large multi-site deployment
     * accumulates few rows per day; this batch clears far more than a normal day's expiries at once.
     */
    data class Impression(
        val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
        val pruneBatchSize: Int = DEFAULT_PRUNE_BATCH_SIZE,
    ) {
        init {
            // A zero/negative window makes the cutoff day today-or-later, so the reaper would prune the
            // very days the dashboard still reports on — silently zeroing recent impression counts and
            // the interaction rate. Refuse the misconfig at startup.
            require(!retention.isZero && !retention.isNegative) {
                "complyr.impression.retention must be a positive duration (was $retention)"
            }
            // A non-positive batch size makes the reaper delete nothing and loop until its per-run cap;
            // refuse it at startup rather than silently disabling the prune.
            require(pruneBatchSize > 0) {
                "complyr.impression.prune-batch-size must be positive (was $pruneBatchSize)"
            }
        }

        companion object {
            // Must exceed 2× the widest analytics preset (90-day view → 180-day prior comparison window) so
            // a pruned counter can never affect a shown figure or a period-over-period delta; 210 = 180 + a
            // month of headroom for reaper lag and boundary days. Still tiny: one row per (site, day).
            const val DEFAULT_RETENTION_DAYS = 210L

            // Rows per prune transaction. The (site, day) grain means few rows accrue per day, so this
            // clears well beyond a normal day's expiries in one short, vacuum-friendly transaction.
            const val DEFAULT_PRUNE_BATCH_SIZE = 10_000
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
        // Rows the retention reaper deletes per transaction (see [com.complyr.scan.PublicScanReaper]).
        // Each deleted scan cascades to its `public_scan_cookies` (up to [maxCookies] rows), so this is
        // kept an order of magnitude below the consent-idempotency batch: the cascade fan-out is what
        // sizes the transaction, not the scan-row count. Large enough that a day's expiries drain in
        // one batch, small enough to stay vacuum-friendly. The prune schedule is the raw
        // `complyr.scan.public-scan-prune-cron` property read by `@Scheduled`, not typed here.
        val publicScanPruneBatchSize: Int = DEFAULT_PUBLIC_SCAN_PRUNE_BATCH_SIZE,
        // Scheduled re-scan job tuning (ADR-17; see [com.complyr.scan.ScheduledRescanJob]).
        // [rescanBatchSize] caps how many candidate sites one nightly run examines (oldest-scanned
        // first), so a backlog drains across successive nights instead of dumping a burst on the single
        // Chromium worker. [rescanJitterWindow] is the span across which the run spreads each enqueued
        // job's `available_at` (via a per-run SecureRandom), so a batch of due sites doesn't all arrive
        // at the worker the instant the job fires. The cron itself is the raw
        // `complyr.scan.rescan-cron` property read by `@Scheduled`, not typed here.
        val rescanBatchSize: Int = DEFAULT_RESCAN_BATCH_SIZE,
        val rescanJitterWindow: Duration = Duration.ofHours(DEFAULT_RESCAN_JITTER_WINDOW_HOURS),
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
            // A non-positive batch size makes the reaper delete nothing and loop until its per-run cap;
            // refuse it at startup rather than silently disabling the retention prune.
            require(publicScanPruneBatchSize > 0) {
                "complyr.scan.public-scan-prune-batch-size must be positive (was $publicScanPruneBatchSize)"
            }
            // A non-positive batch would make the scheduled re-scan job examine (and thus enqueue)
            // nothing, silently freezing all scheduled re-scans; refuse it at startup.
            require(rescanBatchSize > 0) {
                "complyr.scan.rescan-batch-size must be positive (was $rescanBatchSize)"
            }
            // Zero is legitimate (enqueue every due site at `now`, no spread); only a negative window is
            // nonsense — it would make the jitter offset negative and back-date `available_at`.
            require(!rescanJitterWindow.isNegative) {
                "complyr.scan.rescan-jitter-window must not be negative (was $rescanJitterWindow)"
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

            // Scans deleted per prune transaction. Kept well below the consent-idempotency batch (10k)
            // because each row cascade-deletes up to `maxCookies` child rows: 500 scans * up to 500
            // cookies bounds the transaction's row churn while still clearing a normal day's expiries
            // in a single batch.
            const val DEFAULT_PUBLIC_SCAN_PRUNE_BATCH_SIZE = 500

            // Candidate sites examined per nightly re-scan run. Comfortably above the MVP site count so
            // one run drains the whole due set in steady state, yet bounded so a large backlog (or a bug)
            // can't enqueue an unbounded burst at the single Chromium worker in one tick.
            const val DEFAULT_RESCAN_BATCH_SIZE = 200

            // Spread the batch's `available_at` across 2h so a nightly wave of due sites trickles into the
            // worker instead of arriving as one thundering herd. Offset from the crawl budget (§4.4 caps a
            // job at 10min), so even a full batch clears well within the window.
            const val DEFAULT_RESCAN_JITTER_WINDOW_HOURS = 2L
        }
    }

    /**
     * Domain-verification tuning (ADR-17) for the app-initiated outbound fetch in
     * [com.complyr.site.SiteVerificationFetcher] and the TXT lookup in
     * [com.complyr.site.DnsTxtLookup].
     *
     * Unlike the scanner, this request originates from the `api` container, which *does* have routes to
     * internal services — so every bound here is load-bearing, not cosmetic. [totalBudget] caps the HTTP
     * half of a verify operation (a synchronous request a user is waiting on, holding a Tomcat thread);
     * [requestTimeout] caps one hop; [maxRedirects] bounds a redirect chain that would otherwise let a
     * hostile host walk us around the SSRF checks; [maxBodyBytes] bounds the response we buffer, so a
     * multi-gigabyte or endless body cannot exhaust heap.
     *
     * [dnsTimeout] bounds the TXT lookup, and it sits *outside* [totalBudget] — the two halves are
     * separate operations. Its real cost is also worse than its face value: the JDK's DNS provider
     * applies the timeout per nameserver per retry and falls back to TCP on a truncated answer, so a
     * lookup can cost roughly `2 × dnsTimeout × (nameservers in /etc/resolv.conf)`. Budget a verify at
     * `totalBudget + ~6 × dnsTimeout` when sizing the Tomcat pool and the endpoint's rate-limit tier.
     *
     * **Every bound below has a ceiling as well as a floor.** These values are env-tunable, they decide
     * how long a user-triggered request may hold one of 50 Tomcat threads, and this is the one endpoint
     * an unauthenticated-ish attacker can aim at a host that never answers. A fat-fingered
     * `total-budget: 10m` is therefore not a slow endpoint, it is an outage — so it fails at startup
     * instead. The ceilings are deliberately generous; they exist to catch a wrong *unit*, not to tune.
     */
    data class Verification(
        val connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS),
        val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS),
        val totalBudget: Duration = Duration.ofSeconds(DEFAULT_TOTAL_BUDGET_SECONDS),
        val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
        val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
        val dnsTimeout: Duration = Duration.ofSeconds(DEFAULT_DNS_TIMEOUT_SECONDS),
    ) {
        init {
            requireInRange("connect-timeout", connectTimeout, MAX_TIMEOUT)
            requireInRange("request-timeout", requestTimeout, MAX_TIMEOUT)
            // A zero/negative budget would abort every verification before the first hop.
            requireInRange("total-budget", totalBudget, MAX_TOTAL_BUDGET)
            // One hop must fit inside the whole operation, or the per-hop timeout is unreachable and the
            // budget is the only real bound — a misconfig that silently changes which limit applies.
            require(requestTimeout <= totalBudget) {
                "complyr.verification.request-timeout ($requestTimeout) must not exceed total-budget ($totalBudget)"
            }
            // Likewise: a connect that may outlast the whole operation makes the budget the only bound.
            require(connectTimeout <= totalBudget) {
                "complyr.verification.connect-timeout ($connectTimeout) must not exceed total-budget ($totalBudget)"
            }
            // Negative would be nonsense; 0 is legitimate (refuse to follow redirects at all). The ceiling
            // matters for a subtler reason too: SiteVerificationFetcher loops `maxRedirects + 1` times, so
            // Int.MAX_VALUE overflows to a negative count and silently performs *zero* hops.
            require(maxRedirects in 0..MAX_REDIRECTS_CEILING) {
                "complyr.verification.max-redirects must be between 0 and $MAX_REDIRECTS_CEILING (was $maxRedirects)"
            }
            // A non-positive cap would read an empty body and fail every snippet check; the ceiling keeps a
            // hostile endless body from being buffered toward 2GB on a 4GB box.
            require(maxBodyBytes in 1..MAX_BODY_BYTES_CEILING) {
                "complyr.verification.max-body-bytes must be between 1 and $MAX_BODY_BYTES_CEILING (was $maxBodyBytes)"
            }
            // Validated in milliseconds, not as a Duration, because milliseconds is what reaches the JNDI
            // provider: PT0.0009S is a positive Duration whose toMillis() is 0, and 0 makes the provider's
            // `Selector.select(0)` block *forever* — one dropped DNS reply would then pin a Tomcat thread
            // permanently. At the other end, a value past Int.MAX_VALUE ms makes the provider's internal
            // Integer.parseInt throw an unchecked exception straight past DnsTxtLookup's catch.
            require(dnsTimeout.toMillis() in MIN_DNS_TIMEOUT_MILLIS..MAX_DNS_TIMEOUT_MILLIS) {
                "complyr.verification.dns-timeout must be between ${MIN_DNS_TIMEOUT_MILLIS}ms and " +
                    "${MAX_DNS_TIMEOUT_MILLIS}ms (was $dnsTimeout)"
            }
        }

        private fun requireInRange(
            name: String,
            value: Duration,
            ceiling: Duration,
        ) {
            require(!value.isZero && !value.isNegative && value <= ceiling) {
                "complyr.verification.$name must be a positive duration of at most $ceiling (was $value)"
            }
        }

        companion object {
            const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 5L
            const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 5L

            // A user is waiting on this synchronously, so the whole thing must resolve well inside a
            // patience window — and it holds a Tomcat thread for its duration (the pool is 50 on a CX22).
            const val DEFAULT_TOTAL_BUDGET_SECONDS = 10L

            // Enough for the usual apex→www / http→https hops, few enough to bound a redirect walk.
            const val DEFAULT_MAX_REDIRECTS = 3

            // The snippet lives in <head>, so 512KB reaches it on any real page while bounding what a
            // hostile host can make us buffer. Truncation is safe: it can only cause a false negative.
            const val DEFAULT_MAX_BODY_BYTES = 512 * 1024

            const val DEFAULT_DNS_TIMEOUT_SECONDS = 2L

            // Ceilings. Not tuning knobs — sanity bounds that turn a wrong unit into a startup failure
            // rather than a Tomcat pool that drains under a handful of hostile verifications.
            val MAX_TIMEOUT: Duration = Duration.ofSeconds(30)
            val MAX_TOTAL_BUDGET: Duration = Duration.ofSeconds(30)
            const val MAX_REDIRECTS_CEILING = 10
            const val MAX_BODY_BYTES_CEILING = 8 * 1024 * 1024
            const val MIN_DNS_TIMEOUT_MILLIS = 100L
            const val MAX_DNS_TIMEOUT_MILLIS = 30_000L
        }
    }

    /**
     * Billing / Stripe tuning (docs/ARCHITECTURE.md §10). [trialPeriod] is the no-card trial window,
     * measured from `users.created_at` — the trial is derived, not a subscription row, so signup stays
     * untouched (see [com.complyr.billing.PlanResolver]). [trialConsentEventCap] bounds consent
     * ingestion during that trial so it can't be used as unbounded free production capacity; it caps
     * INGESTION only and never blocks recording an already-accepted event (CLAUDE.md constraint #3).
     *
     * [stripeSecretKey] and the per-plan [priceIds] are the environment-specific Stripe credentials
     * (see [com.complyr.billing.StripeConfig] / [com.complyr.billing.BillingService]). They are bound
     * from env vars with NO default in application.yml, so dev/prd fail fast at startup if unset — and
     * crucially each environment supplies its OWN values: local/dev use Stripe TEST-mode keys+prices
     * (`sk_test_…`, test-mode `price_…`), prd uses LIVE-mode ones (`sk_live_…`, live `price_…`). A
     * TEST-mode price id is invalid against a live key and vice versa, so they must never be shared
     * across environments. The empty defaults below exist only so the no-arg `Billing()` used by the
     * data-model unit tests still constructs; a real secret/price is required to actually reach Stripe.
     *
     * [webhookSecret] is the endpoint's Stripe signing secret (`whsec_…`), used by
     * [com.complyr.billing.StripeApiGateway.parseWebhookEvent] to verify every inbound webhook. Like
     * the secret key it is env-specific (each environment registers its own webhook endpoint and gets
     * its own secret) and bound from `${'$'}{STRIPE_WEBHOOK_SECRET}` with no default in application.yml
     * so dev/prd fail fast if unset; the empty default only lets the no-arg `Billing()` construct.
     *
     * [stripeEventRetention] / [stripeEventPruneBatchSize] tune the `stripe_events` inbox reaper (see
     * [com.complyr.billing.StripeWebhookReaper]). Retention only has to outlive Stripe's redelivery
     * window (a few days) since the row exists solely for idempotency/audit; a redacted processed row
     * carries no PII but is still pruned to bound the table. Batch size chunks a backlog into short,
     * vacuum-friendly DELETE transactions. The prune schedule itself is the raw
     * `complyr.billing.stripe-event-prune-cron` property read by `@Scheduled`, not typed here.
     *
     * [automaticTax] toggles Stripe Tax on Checkout: on in prd (Stripe Tax configured), off-able per
     * environment where Tax isn't set up (a Checkout with automatic_tax against a Tax-less account
     * errors), so it is env-overridable rather than hard-coded on.
     */
    data class Billing(
        val trialPeriod: Duration = Duration.ofDays(DEFAULT_TRIAL_DAYS),
        val trialConsentEventCap: Long = DEFAULT_TRIAL_CONSENT_EVENT_CAP,
        val stripeSecretKey: String = "",
        val webhookSecret: String = "",
        val priceIds: PriceIds = PriceIds(),
        val automaticTax: Boolean = true,
        // Dashboard-relative return paths Stripe redirects back to after Checkout / Portal. Combined
        // with `complyr.app-base-url` into absolute URLs by [com.complyr.billing.BillingService].
        val checkoutSuccessPath: String = DEFAULT_CHECKOUT_SUCCESS_PATH,
        val checkoutCancelPath: String = DEFAULT_CHECKOUT_CANCEL_PATH,
        val portalReturnPath: String = DEFAULT_PORTAL_RETURN_PATH,
        // `stripe_events` inbox retention + prune batch (see [com.complyr.billing.StripeWebhookReaper]).
        val stripeEventRetention: Duration = Duration.ofDays(DEFAULT_STRIPE_EVENT_RETENTION_DAYS),
        val stripeEventPruneBatchSize: Int = DEFAULT_STRIPE_EVENT_PRUNE_BATCH_SIZE,
        // How far ahead of the trial's end the "ending soon" reminder goes out, and how many accounts
        // one nightly run will remind (see [com.complyr.billing.TrialEndingReminderJob]).
        val trialReminderLeadTime: Duration = Duration.ofDays(DEFAULT_TRIAL_REMINDER_LEAD_DAYS),
        val trialReminderBatchSize: Int = DEFAULT_TRIAL_REMINDER_BATCH_SIZE,
    ) {
        init {
            require(!trialPeriod.isZero && !trialPeriod.isNegative) {
                "complyr.billing.trial-period must be a positive duration (was $trialPeriod)"
            }
            require(trialConsentEventCap > 0) {
                "complyr.billing.trial-consent-event-cap must be positive (was $trialConsentEventCap)"
            }
            // A zero/negative window makes cutoff >= now, so the reaper would delete rows for events
            // Stripe may still redeliver — reopening the dedupe window. Refuse the misconfig at startup.
            require(!stripeEventRetention.isZero && !stripeEventRetention.isNegative) {
                "complyr.billing.stripe-event-retention must be a positive duration (was $stripeEventRetention)"
            }
            // A non-positive batch size makes the reaper delete nothing and loop until its per-run cap.
            require(stripeEventPruneBatchSize > 0) {
                "complyr.billing.stripe-event-prune-batch-size must be positive (was $stripeEventPruneBatchSize)"
            }
            // A zero/negative lead makes the candidate window empty, so the reminder would silently never
            // fire; a lead longer than the trial itself would mail people on their signup day.
            require(!trialReminderLeadTime.isZero && !trialReminderLeadTime.isNegative) {
                "complyr.billing.trial-reminder-lead-time must be a positive duration (was $trialReminderLeadTime)"
            }
            require(trialReminderLeadTime < trialPeriod) {
                "complyr.billing.trial-reminder-lead-time ($trialReminderLeadTime) must be shorter than " +
                    "trial-period ($trialPeriod)"
            }
            require(trialReminderBatchSize > 0) {
                "complyr.billing.trial-reminder-batch-size must be positive (was $trialReminderBatchSize)"
            }
        }

        /**
         * Stripe price id per plan. Env-specific (test-mode vs live-mode); see [Billing]. The
         * plan→price mapping lives in [com.complyr.billing.BillingService] so this config-binding
         * layer stays free of any dependency on the billing domain's `Plan` type.
         */
        data class PriceIds(
            val starter: String = "",
            val pro: String = "",
            val business: String = "",
        )

        companion object {
            const val DEFAULT_TRIAL_DAYS = 14L

            // Enough headroom for a genuine evaluation on a small site, low enough that running
            // production traffic through the free trial hits the cap and prompts an upgrade.
            const val DEFAULT_TRIAL_CONSENT_EVENT_CAP = 1_000L

            const val DEFAULT_CHECKOUT_SUCCESS_PATH = "/billing?checkout=success"
            const val DEFAULT_CHECKOUT_CANCEL_PATH = "/billing?checkout=cancel"
            const val DEFAULT_PORTAL_RETURN_PATH = "/billing"

            // A few days comfortably outlives Stripe's redelivery window (retries taper over ~72h),
            // so the inbox keeps deduping every real re-delivery while the reaper bounds table growth.
            const val DEFAULT_STRIPE_EVENT_RETENTION_DAYS = 30L

            // Rows per prune transaction. Steady-state webhook volume is tiny, so this only matters
            // when draining a backlog; kept small for short, vacuum-friendly DELETEs.
            const val DEFAULT_STRIPE_EVENT_PRUNE_BATCH_SIZE = 500

            // Three days before the 14-day trial lapses: long enough to act on (a small business needs to
            // find a card and get sign-off), short enough that the deadline still feels real.
            const val DEFAULT_TRIAL_REMINDER_LEAD_DAYS = 3L

            // Accounts reminded per nightly run. Well above any plausible signup cohort at MVP scale, so
            // it is a runaway guard rather than a throttle we expect to hit.
            const val DEFAULT_TRIAL_REMINDER_BATCH_SIZE = 500
        }
    }

    /**
     * Transactional email delivery selection. [provider] picks exactly one [com.complyr.notify.EmailSender]
     * bean via `@ConditionalOnProperty` — `smtp` (the default; Mailpit locally) or `brevo` (Brevo's HTTP
     * API in dev/prd, an EU processor). The From address is the top-level [mailFrom]; [Brevo.senderName]
     * is only the display name paired with it.
     *
     * [Brevo.apiKey] is bound from `${'$'}{BREVO_API_KEY}` with an empty default so the no-arg `Mail()`
     * (SMTP path, and unrelated unit tests) still constructs; the [com.complyr.notify.BrevoEmailSender]
     * bean — created only when `provider=brevo` — rejects a blank key at startup, so dev/prd fail fast if
     * it is unset. [Brevo.baseUrl] is the EU API host (overridable so a mock server can stand in under test).
     */
    data class Mail(
        val provider: String = DEFAULT_PROVIDER,
        val brevo: Brevo = Brevo(),
    ) {
        init {
            // `provider` is always materialised (application.yml defaults it), so a typo would match
            // neither sender's case-sensitive @ConditionalOnProperty, leaving no EmailSender bean and an
            // opaque NoSuchBeanDefinitionException elsewhere. Fail fast here with the real cause instead.
            require(provider in SUPPORTED_PROVIDERS) {
                "complyr.mail.provider (MAIL_PROVIDER) must be one of $SUPPORTED_PROVIDERS, was '$provider'"
            }
        }

        data class Brevo(
            val apiKey: String = "",
            val baseUrl: String = DEFAULT_BREVO_BASE_URL,
            val senderName: String = DEFAULT_BREVO_SENDER_NAME,
        )

        companion object {
            const val DEFAULT_PROVIDER = "smtp"
            const val DEFAULT_BREVO_BASE_URL = "https://api.brevo.com"
            const val DEFAULT_BREVO_SENDER_NAME = "Complyr"
            val SUPPORTED_PROVIDERS = setOf("smtp", "brevo")
        }
    }

    /**
     * Error-tracking (Sentry) configuration — see [com.complyr.common.SentryConfig] and ADR-15.
     *
     * [Sentry.dsn] is bound from `${'$'}{SENTRY_DSN_BACKEND}` with an EMPTY default, and a blank DSN
     * DISABLES Sentry entirely (no init, no appender) — so local and any environment without a DSN run
     * with error tracking off, and only a real DSN turns it on. When set it MUST be a Sentry EU-region
     * DSN (host `*.de.sentry.io`); the [com.complyr.common.SentryConfig] bean refuses a non-EU DSN at
     * startup for GDPR data residency (CLAUDE.md #2). No PII is ever sent: `send-default-pii` stays off
     * and a beforeSend scrub drops any request/user context (CLAUDE.md #4).
     *
     * [Sentry.environment] tags events (local|dev|prd); [Sentry.release] is an optional build marker
     * (e.g. the deployed image tag). [Sentry.tracesSampleRate] is the performance-tracing sample
     * fraction — 0.0 (off) by default, since we run no OpenTelemetry agent.
     */
    data class Observability(
        val sentry: Sentry = Sentry(),
    ) {
        data class Sentry(
            val dsn: String = "",
            val environment: String = DEFAULT_ENVIRONMENT,
            val release: String = "",
            val tracesSampleRate: Double = DEFAULT_TRACES_SAMPLE_RATE,
        ) {
            init {
                require(tracesSampleRate in 0.0..1.0) {
                    "complyr.observability.sentry.traces-sample-rate must be within [0.0, 1.0] " +
                        "(was $tracesSampleRate)"
                }
            }

            companion object {
                const val DEFAULT_ENVIRONMENT = "local"
                const val DEFAULT_TRACES_SAMPLE_RATE = 0.0
            }
        }
    }
}
