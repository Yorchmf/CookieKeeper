package eu.cookiekeeper.common

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
 *  - **EXPORT tier (tight):** the Business-plan bulk-export downloads
 *    (`GET /api/v1/sites/{id}/analytics/export.csv` and `.../evidence-pack.zip`). The evidence pack
 *    streams the trailing 30 days of consent audit evidence *and* every published policy-language
 *    HTML *and* the latest scan summary in a single request — by far the heaviest assembly the `api`
 *    container performs. Neither endpoint is polled, so on the generous GENERAL tier one account
 *    could loop the pack and saturate the box. The server-side entitlement gate narrows *who* reaches
 *    it; this bounds *how hard* they can. Note this tier bounds request *arrival rate*, not concurrent
 *    stream occupancy — several overlapping pack streams still each pin a request thread, so the
 *    (tuned) Tomcat thread pool, not this filter, is the concurrency ceiling.
 *  - **CONTACT tier (tight):** `POST /api/v1/support/contact` — the in-app contact form. Every accepted
 *    call composes and sends an email to our support inbox, so an authenticated loop would flood the
 *    inbox and burn the shared mail-provider budget. A real customer submits it a handful of times ever.
 *    Matched by exact path (like VERIFY's suffix match) and ordered before the GENERAL fallthrough.
 *  - **POLICY tier (tight):** `POST /api/v1/sites/{id}/policy` — cookie-policy (re)generation, the
 *    heaviest authed write (renders every configured language, behind a per-site advisory lock). The
 *    byte-identical debounce already collapses honest re-clicks, so this bounds the one abuse it can't:
 *    varying a byte per request to mint a fresh version each call. Uniquely method-scoped — the *same*
 *    path also serves the cheap `GET` current-policy read the dashboard hits on every policy-page view,
 *    which must stay on GENERAL — so only `POST` is matched, then ordered before the GENERAL fallthrough.
 *  - **ACCOUNT tier (tight):** every call under `/api/v1/account` — the customer's own GDPR and
 *    credential surface (ADR-20). `POST /account/delete` and `POST /account/password` both re-verify the
 *    account password, so leaving them on the generous GENERAL tier made them a 300-guesses-per-minute
 *    password oracle *and* a bcrypt CPU-exhaustion primitive; `GET /account/export.json` assembles the
 *    entire account in memory, several queries per site. All are actions a real customer performs a
 *    handful of times ever.
 *  - **GENERAL tier (generous backstop):** all other authed `/api/v1` calls — e.g.
 *    `POST /api/v1/sites` (each synchronously enqueues a Chromium scan) or consent-log reads (keyset
 *    queries fanning across monthly partitions). A cap normal dashboard use never reaches, but which
 *    bounds amplification abuse.
 *
 * Registered just ahead of [ErasedAccountFilter] and downstream of Spring Security's filter
 * chain — after the bearer token has been resolved into the `SecurityContext`. Requests with no
 * authenticated JWT are passed straight through: unauthenticated traffic is the IP filter's and the
 * security layer's concern, not this one's. The `/api/v1/billing/webhook` endpoint is unauthenticated
 * (Stripe cannot present a JWT) and is excluded from tier matching. User ids are bucket keys only —
 * never logged, never persisted (CLAUDE.md #4). Edge rate limiting (Cloudflare) layers on top.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class AuthenticatedRateLimitFilter(
    private val properties: CookieKeeperProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val buckets = RateLimitBuckets(properties.rateLimit.maxTrackedKeys)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        HttpMethod.OPTIONS.matches(request.method) || tierFor(request.method, RequestPaths.tierPath(request)) == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val tier = tierFor(request.method, RequestPaths.tierPath(request)) ?: return filterChain.doFilter(request, response)
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

    private fun tierFor(
        method: String,
        uri: String,
    ): Tier? =
        when {
            // Unauthenticated by construction (Stripe cannot send a JWT) — no principal to key on.
            uri == BILLING_WEBHOOK_PATH -> null
            uri == BILLING_PATH || uri.startsWith("$BILLING_PATH/") -> Tier.BILLING
            uri == ACCOUNT_PATH || uri.startsWith("$ACCOUNT_PATH/") -> Tier.ACCOUNT
            // Exact match: the contact form is the only endpoint under `/api/v1/support`, and each accepted
            // call sends an email. Ordered before the GENERAL fallthrough, which would otherwise swallow it.
            uri == CONTACT_PATH -> Tier.CONTACT
            // The per-site sub-resources carry their own tighter tiers (verify/export/policy); anything
            // else under the prefix is a normal GENERAL call. Split out to keep this matcher simple.
            uri.startsWith(SITES_PATH_PREFIX) -> sitesTier(method, uri)
            uri.startsWith(API_PREFIX) -> Tier.GENERAL
            else -> null
        }

    /**
     * Resolves the tier for a path under `/api/v1/sites/`, falling back to [Tier.GENERAL] for any sub-resource
     * without a dedicated bucket. Every suffix here must be exact so a sibling sub-resource is never dragged
     * onto a tighter tier: `GET /api/v1/sites/{id}/scans` is polled every 3s by the dashboard and must stay
     * GENERAL. Matching is method-blind (as everywhere in this filter) *except* for policy generation, which
     * gates on `POST` so the cheap `GET` current-policy read on the same path stays GENERAL.
     */
    private fun sitesTier(
        method: String,
        uri: String,
    ): Tier =
        when {
            uri.endsWith(VERIFY_SUFFIX) -> Tier.VERIFY
            // The Business bulk-export downloads, matched by exact suffix (like VERIFY).
            uri.endsWith(EXPORT_CSV_SUFFIX) || uri.endsWith(EVIDENCE_PACK_SUFFIX) -> Tier.EXPORT
            // Cookie-policy (re)generation — the heaviest authed write (renders every language, behind a
            // per-site advisory lock). The ONLY method-scoped tier: the *same* path serves both this heavy
            // `POST` and the cheap `GET` current-policy read the policy page hits on every view, so a
            // method-blind suffix match would drag that read onto this tight write bucket. Gating on POST
            // keeps the read on GENERAL below.
            HttpMethod.POST.matches(method) && uri.endsWith(POLICY_SUFFIX) -> Tier.POLICY
            else -> Tier.GENERAL
        }

    private fun capacityFor(tier: Tier): Long =
        when (tier) {
            Tier.BILLING -> properties.rateLimit.authBillingPerMinute
            Tier.ACCOUNT -> properties.rateLimit.authAccountPerMinute
            Tier.VERIFY -> properties.rateLimit.authVerifyPerMinute
            Tier.EXPORT -> properties.rateLimit.authExportPerMinute
            Tier.CONTACT -> properties.rateLimit.authContactPerMinute
            Tier.POLICY -> properties.rateLimit.authPolicyPerMinute
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

    private enum class Tier { BILLING, ACCOUNT, VERIFY, EXPORT, CONTACT, POLICY, GENERAL }

    companion object {
        const val API_PREFIX = "/api/v1/"
        const val BILLING_PATH = "/api/v1/billing"
        const val ACCOUNT_PATH = "/api/v1/account"
        const val CONTACT_PATH = "/api/v1/support/contact"
        const val BILLING_WEBHOOK_PATH = "/api/v1/billing/webhook"
        const val SITES_PATH_PREFIX = "/api/v1/sites/"
        const val VERIFY_SUFFIX = "/verify"
        const val EXPORT_CSV_SUFFIX = "/analytics/export.csv"
        const val EVIDENCE_PACK_SUFFIX = "/analytics/evidence-pack.zip"
        const val POLICY_SUFFIX = "/policy"

        // Buckets refill over a 1-minute window, so a drained caller can retry after at most 60s.
        private const val RETRY_AFTER_SECONDS = "60"
    }
}
