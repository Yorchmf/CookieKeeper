package com.complyr.site

import com.complyr.common.ComplyrProperties
import com.complyr.scan.ScanCrawlPolicy
import com.complyr.scan.ScanTargetException
import com.complyr.scan.ScanTargetValidator
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Fetches a customer domain's homepage HTML so [SnippetMatcher] can look for the embed snippet
 * (ADR-17). This is the only place Complyr makes an app-initiated request to a *customer-controlled*
 * host from the `api` container — the container that, unlike `scanner`, has routes to Postgres and the
 * dashboard. Treat every bound below as load-bearing.
 *
 * Layered guards, in order, on every hop (not just the first — a redirect is a fresh, attacker-chosen
 * target and gets the identical treatment):
 *  1. https/http only, and only on the scheme's default port. An explicit port is how an SSRF probe
 *     sweeps internal services (`302 → http://victim:9200/`), and no real site needs one to be
 *     verifiable. [fetch]'s `allowedPort` widens this for loopback tests only; production never passes it.
 *  2. [ScanCrawlPolicy.sameHostFamily] — a redirect may move within the domain (apex↔www, a sub-domain)
 *     but never off it. Following an arbitrary `Location` would hand target selection to the attacker.
 *  3. [ScanTargetValidator.validate] — the host must resolve, and every address it resolves to must be
 *     publicly routable. Fails closed.
 *  4. [HttpClient.Redirect.NEVER] with a manual, counted redirect loop, so guards 1-3 cannot be skipped
 *     by the client following a hop on our behalf.
 *  5. A whole-operation deadline, enforced in two different ways because the header wait and the body
 *     read need two different mechanisms — see [readBody] for why cancellation alone is not enough.
 *  6. The body is read into a [ComplyrProperties.Verification.maxBodyBytes] cap and truncation is
 *     silent-by-design: a truncated page can only ever produce a false *negative*.
 *  7. We only ever *build* `GET /` — no query or fragment is appended, so a page that reflects its query
 *     string back into the response cannot be tricked into echoing the site key. (A redirect may still
 *     send us to a path with a query; that is the customer's own server choosing its own URL, and it is
 *     re-guarded like any other hop.) No cookies (the client has no cookie handler), no credentials.
 *
 * **Nothing about the outcome reaches the customer.** Refusal, DNS failure, timeout, a 500, and the
 * wrong content type all return the same `null`; only the server log distinguishes them. Surfacing the
 * difference would turn this endpoint into an internal-network mapping oracle. Logs carry no stack
 * traces for target-side failures either — an attacker picks how often those fire, and a stack trace per
 * hostile request is free log-volume amplification.
 *
 * Residual risk (recorded in ADR-17): the validate-then-connect gap is a DNS-rebinding window this
 * class cannot close alone, because the JVM — not us — performs the connect-time resolution. It is
 * mitigated by the JVM's positive-DNS cache (pinned in `backend/Dockerfile`) and, authoritatively, by
 * the egress firewall on the `api` container. Bodies are decoded as UTF-8 regardless of the declared
 * charset; the snippet is pure ASCII, so this costs nothing except on a UTF-16-encoded page.
 */
@Component
class SiteVerificationFetcher(
    private val validator: ScanTargetValidator,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SiteVerificationFetcher::class.java)

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            // Guard 4: we follow redirects ourselves so every hop is re-validated.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.verification.connectTimeout)
            .build()

    /**
     * One daemon thread, shared by every verification, whose only job is to close a response body that
     * has stopped producing bytes. It never does I/O of its own and never blocks, so one is enough.
     *
     * Built directly rather than via `Executors.newSingleThreadScheduledExecutor`, whose
     * `DelegatedScheduledExecutorService` wrapper makes `removeOnCancelPolicy` unreachable. It defaults
     * to false, which means every *successfully* read body — the normal case — leaves a cancelled task
     * sitting in the delay queue until its original deadline, strongly holding the response stream and
     * therefore its connection. The purge is what keeps a fast verification from costing as much as a
     * slow one.
     */
    private val watchdog: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "verification-body-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    @PreDestroy
    fun shutdown() {
        watchdog.shutdownNow()
    }

    /**
     * Fetch `https://{domain}/` and return its HTML, or null if the domain is unreachable, refused, or
     * did not answer with HTML. Never throws for a target-side problem — callers get one undifferentiated
     * miss (see the class docs on why). A domain that is not even URI-legal is one more such miss: it
     * must not become the 500 that distinguishes itself from every other outcome.
     */
    fun fetchHomepage(domain: String): String? =
        runCatching { URI("https://$domain/") }
            .getOrNull()
            ?.let { fetch(it, domain) }

    /**
     * The guarded redirect loop, entered at [entry].
     *
     * [allowedPort] is the one guard that is relaxed for tests: production calls [fetchHomepage], which
     * never passes it, so guard 1 provably reduces to "80 and 443 only". Tests pass their loopback
     * server's ephemeral port so the *identical*, fully-guarded code path can be exercised without a real
     * TLS host on :443. Deriving this from `entry.port` instead — as an earlier version did — would have
     * meant a `domain` carrying `:9200` silently authorising its own port sweep.
     */
    @Suppress("ReturnCount") // each exit is a distinct give-up condition; nesting them would obscure that
    internal fun fetch(
        entry: URI,
        domain: String,
        allowedPort: Int = NO_PORT,
    ): String? {
        val deadline = clock.instant().plus(properties.verification.totalBudget)
        var target = entry

        repeat(properties.verification.maxRedirects + 1) {
            val outcome = hop(target, domain, allowedPort, deadline) ?: return null
            when (outcome) {
                is HopOutcome.Html -> return outcome.body
                is HopOutcome.Redirect -> target = outcome.location ?: return null
            }
        }
        log.warn("Verification fetch for {} exceeded {} redirects", domain, properties.verification.maxRedirects)
        return null
    }

    /** One guarded request. Null means "give up"; a [HopOutcome.Redirect] means "re-enter with its target". */
    @Suppress("ReturnCount") // each return is a distinct fail-closed gate; folding them hides which one fired
    private fun hop(
        target: URI,
        domain: String,
        allowedPort: Int,
        deadline: Instant,
    ): HopOutcome? {
        if (!isAllowedTarget(target, domain, allowedPort)) return null
        val remaining = remainingUntil(deadline)
        if (remaining.isZero || remaining.isNegative) {
            log.warn("Verification fetch for {} ran out of budget", domain)
            return null
        }
        val response = awaitHeaders(target, domain, remaining) ?: return null
        return readBody(response, domain, deadline)
    }

    /** Guards 1-3, applied identically to the initial URL and to every redirect target. */
    @Suppress("ReturnCount") // sequential fail-closed guards; each one is a reject with its own log line
    private fun isAllowedTarget(
        target: URI,
        domain: String,
        allowedPort: Int,
    ): Boolean {
        val scheme = target.scheme?.lowercase()
        val host = target.host?.lowercase()
        if (host == null || (scheme != "http" && scheme != "https")) {
            log.warn("Verification fetch for {} refused a non-http(s) target", domain)
            return false
        }
        // NO_PORT means "no explicit port". An explicit one is tolerated only when it is the scheme
        // default or the test allowance, so `https://victim.com:9200/` (an internal Elasticsearch probe)
        // never gets dialled. In production allowedPort is NO_PORT, i.e. "80 and 443 only".
        val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
        if (target.port != NO_PORT && target.port != defaultPort && target.port != allowedPort) {
            log.warn("Verification fetch for {} refused a non-default port {}", domain, target.port)
            return false
        }
        if (!ScanCrawlPolicy.sameHostFamily(host, domain)) {
            log.warn("Verification fetch for {} refused an off-domain redirect to host {}", domain, host)
            return false
        }
        return try {
            validator.validate(host)
            true
        } catch (ex: ScanTargetException) {
            // The reason (DNS failure vs. a private address) is deliberately kept out of the response.
            log.warn("Verification fetch for {} refused host {}: {}", domain, host, ex.reason.code)
            false
        }
    }

    /**
     * Wait for the response *headers* only, bounded by [budget]. This is the one phase where cancelling
     * the exchange genuinely aborts it, because the exchange really is still in flight.
     */
    private fun awaitHeaders(
        target: URI,
        domain: String,
        budget: Duration,
    ): HttpResponse<InputStream>? {
        val request =
            HttpRequest
                .newBuilder(target)
                .GET()
                .timeout(minOf(properties.verification.requestTimeout, budget))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()

        val exchange = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        return try {
            exchange.get(budget.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abandon(exchange, domain)
            log.warn("Verification fetch for {} timed out waiting for response headers", domain)
            null
        } catch (ex: ExecutionException) {
            // Connection refused, TLS failure, per-hop HttpTimeoutException, malformed response…
            log.warn("Verification fetch for {} failed: {}", domain, ex.cause?.javaClass?.simpleName)
            null
        } catch (_: InterruptedException) {
            abandon(exchange, domain)
            Thread.currentThread().interrupt()
            log.warn("Verification fetch for {} was interrupted", domain)
            null
        }
    }

    /**
     * Stop waiting on an exchange, and make sure nothing is left holding a connection either way.
     *
     * `cancel` alone is not enough, and the gap is attacker-tunable: a host that answers just as our
     * budget expires wins the race, `cancel` returns false because the future is already complete, and
     * the `HttpResponse<InputStream>` nobody is left holding keeps its connection and file descriptor
     * for the life of the container. The completion callback is registered *first* so there is no window
     * in which neither mechanism owns the body; it fires immediately if the exchange is already done,
     * and with a null response if the cancel wins.
     */
    private fun abandon(
        exchange: CompletableFuture<HttpResponse<InputStream>>,
        domain: String,
    ) {
        exchange.whenComplete { response, _ -> response?.body()?.let { closeQuietly(it, domain) } }
        exchange.cancel(true)
    }

    /**
     * Classify the response, reading the body **on this thread** under a watchdog that closes the stream
     * when the deadline passes.
     *
     * The obvious shape — `sendAsync(…).thenApply(::classify).get(budget)` then `cancel(true)` — is
     * broken, and was measured to be broken rather than merely suspected. With
     * [HttpResponse.BodyHandlers.ofInputStream] the exchange future completes when the *headers* arrive
     * and the JDK drops the request timer there, so against a host that answers `200 text/html` and then
     * stalls mid-body: `HttpRequest.timeout` never fires, `cancel(true)` returns `false` because the
     * future is already complete, and the reader — parked in `readNBytes` on an async executor — stays
     * `WAITING` forever, holding a thread, a socket and an FD. The request thread returns on time, so it
     * looks fine while the container leaks one of each per hostile verification, with no self-healing.
     *
     * Closing the stream is the only primitive that unparks that read, and it is only available to
     * whoever still holds the handle. So we keep the handle here and hand it to a watchdog.
     */
    @Suppress("ReturnCount") // the shutdown path has to bail before the read; folding it in would hide it
    private fun readBody(
        response: HttpResponse<InputStream>,
        domain: String,
        deadline: Instant,
    ): HopOutcome {
        val body = response.body()
        val budget = maxOf(remainingUntil(deadline), Duration.ZERO)
        // The floor matters: waiting for headers can consume the entire budget, and without it the close
        // would be scheduled at 0ms and race the first read — turning a merely slow (but honest) site
        // into a domain that can never verify, with nothing in the response to explain why. Overshooting
        // the total budget by at most this much is the cheaper error.
        val delayMillis = maxOf(minOf(properties.verification.requestTimeout, budget).toMillis(), MIN_BODY_READ_MILLIS)
        val closer =
            try {
                watchdog.schedule(Runnable { closeQuietly(body, domain) }, delayMillis, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // Only reachable after @PreDestroy. Reading a body with no watchdog is precisely the
                // unbounded park this class exists to prevent, so give up — and give up the way every
                // other target-side failure does, rather than throwing out of a contract that says we don't.
                closeQuietly(body, domain)
                log.warn("Verification fetch for {} abandoned: the body watchdog is shutting down", domain)
                return HopOutcome.Html(null)
            }
        return try {
            classify(response, body)
        } finally {
            closer.cancel(false)
            // Also covers the redirect and non-HTML paths, which never read the body at all: abandoning
            // an unread stream leaks the connection (see the ofInputStream javadoc).
            closeQuietly(body, domain)
        }
    }

    private fun closeQuietly(
        stream: InputStream,
        domain: String,
    ) {
        try {
            stream.close()
        } catch (ex: IOException) {
            log.debug("Verification fetch for {} could not close the response body: {}", domain, ex.javaClass.simpleName)
        }
    }

    private fun remainingUntil(deadline: Instant): Duration = Duration.between(clock.instant(), deadline)

    /** Turn a response into a redirect instruction or capped HTML; anything else is a miss. */
    private fun classify(
        response: HttpResponse<InputStream>,
        body: InputStream,
    ): HopOutcome =
        when {
            response.statusCode() in REDIRECT_STATUSES ->
                HopOutcome.Redirect(
                    response
                        .headers()
                        .firstValue("Location")
                        .map(::resolveLocation)
                        .orElse(null),
                )

            response.statusCode() !in SUCCESS_STATUSES -> HopOutcome.Html(null)
            !isHtml(response) -> HopOutcome.Html(null)
            else -> HopOutcome.Html(readCapped(body))
        }

    /**
     * A `Location` may be relative, but resolving it against the request URI would let a hostile host
     * pick our next target through path tricks; we only ever accept an absolute http(s) URL and then
     * re-run every guard on it. An unparsable value ends the chain.
     */
    private fun resolveLocation(location: String): URI? =
        try {
            URI(location).takeIf { it.isAbsolute }
        } catch (ex: URISyntaxException) {
            log.warn("Verification fetch got an unparsable Location header: {}", ex.reason)
            null
        }

    /**
     * XHTML is included because browsers execute scripts in it exactly as they do in HTML, and a site
     * served that way would otherwise be permanently unverifiable by snippet with no diagnostic — the
     * customer would just be told, forever, that we cannot find a snippet they can see in their source.
     */
    private fun isHtml(response: HttpResponse<InputStream>): Boolean =
        response
            .headers()
            .firstValue("Content-Type")
            .map { it.lowercase().substringBefore(';').trim() }
            .orElse("") in HTML_CONTENT_TYPES

    /**
     * Read at most `maxBodyBytes` and decode as UTF-8 (malformed bytes become U+FFFD rather than
     * throwing — we are pattern-matching, not parsing). Truncation is intentional and unreported: it can
     * only cause a false negative, and the snippet belongs in `<head>` anyway. A watchdog-closed stream
     * surfaces here as an [IOException], which is the same silent miss as any other read failure.
     */
    private fun readCapped(stream: InputStream): String? =
        try {
            String(stream.readNBytes(properties.verification.maxBodyBytes), Charsets.UTF_8)
        } catch (ex: IOException) {
            log.warn("Verification fetch failed while reading the response body: {}", ex.javaClass.simpleName)
            null
        }

    /** What one hop produced. [Html.body] is null for every non-HTML / non-2xx outcome. */
    private sealed interface HopOutcome {
        data class Redirect(
            val location: URI?,
        ) : HopOutcome

        data class Html(
            val body: String?,
        ) : HopOutcome
    }

    private companion object {
        /** What [URI.getPort] returns when the URL carries no explicit port. */
        const val NO_PORT = -1
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        val HTML_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")

        /** Floor on the body-read watchdog delay — see [readBody] for why it cannot be zero. */
        const val MIN_BODY_READ_MILLIS = 1_000L

        // Identifies us honestly so a site operator seeing this in their logs knows what it was, and so
        // a WAF can allowlist it. Fixed — never derived from customer input.
        const val USER_AGENT = "ComplyrVerifier/1.0 (+https://complyr.eu)"

        val SUCCESS_STATUSES = 200..299
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
