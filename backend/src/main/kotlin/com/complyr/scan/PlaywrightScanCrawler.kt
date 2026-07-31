package com.complyr.scan

import com.complyr.common.ComplyrProperties
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.WebSocketRoute
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.ServiceWorkerPolicy
import com.microsoft.playwright.options.WaitUntilState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The production crawler (ARCHITECTURE ADR-5): a headless Chromium via Playwright-for-Java, run only
 * under the `scanner` Spring profile (the browser binaries exist solely in the scanner container).
 *
 * It crawls a verified customer domain in its *before-consent* state — no consent is given, so every
 * cookie/tracker seen is one the site drops without permission — and records the cookies into
 * `scan_cookies`. Classification (category/provider/is_known) is slice 3.
 *
 * SSRF hardening (§4.4) is layered:
 *  1. [ScanTargetValidator.validate] up front — the domain must resolve to a public address.
 *  2. Refuse unverified domains outright (a customer can only point us at a domain they proved they own).
 *  3. A per-request route guard: main-document navigations must stay on the verified host family and
 *     resolve public (blocks off-origin redirects and DNS-rebinding into internal ranges); sub-resources
 *     may reach public third parties (we must observe trackers) but never a private IP literal.
 *  4. Hard per-page and per-job time budgets so a hostile or broken site cannot hang a worker.
 *
 * The authoritative backstop remains the container's network isolation (no inbound ports, no route to
 * internal services) — the app-level checks are defense-in-depth around an unavoidable resolve/connect gap.
 */
@Component
@Profile("scanner")
class PlaywrightScanCrawler(
    private val siteRepository: SiteRepository,
    private val cookieWriter: ScanCookieWriter,
    private val classifier: CookieClassifier,
    private val validator: ScanTargetValidator,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) : ScanCrawler {
    private val log = LoggerFactory.getLogger(PlaywrightScanCrawler::class.java)

    private data class CrawlOutcome(
        val pagesCrawled: Int,
        val cookies: List<Cookie>,
    )

    override fun crawl(claim: ClaimedScan): ScanCrawlResult {
        val site =
            siteRepository.findById(claim.siteId).orElseThrow {
                ScanTargetException(ScanFailureReason.INTERNAL, "scan ${claim.scanId}: site ${claim.siteId} not found")
            }
        if (site.status != SiteStatus.ACTIVE) {
            throw ScanTargetException(ScanFailureReason.INTERNAL, "scan ${claim.scanId}: site ${site.id} is not active")
        }
        // SSRF posture (§4.4): only ever crawl a domain the customer has proven they control.
        if (site.verifiedAt == null) {
            throw ScanTargetException(ScanFailureReason.DOMAIN_NOT_VERIFIED, "scan ${claim.scanId}: domain not verified")
        }
        // Fail closed before launching a browser if the domain resolves anywhere non-public.
        validator.validate(site.domain)

        val outcome = crawlSite(site.domain)
        persistCookies(claim.scanId, outcome.cookies)
        log.info("Scan {} crawled {} page(s), recorded {} cookie(s)", claim.scanId, outcome.pagesCrawled, outcome.cookies.size)
        return ScanCrawlResult(pagesCrawled = outcome.pagesCrawled)
    }

    private fun persistCookies(
        scanId: UUID,
        cookies: List<Cookie>,
    ) {
        // Map -> classify against the signature DB -> persist. A retry reuses the same scan id, so the
        // writer clears the prior attempt's findings before re-recording — atomically, in one
        // transaction (findings are replaceable, not audit evidence).
        val classified = classifier.classify(ScanCookieMapper.toEntities(scanId, cookies))
        cookieWriter.replace(scanId, classified)
    }

    private fun crawlSite(domain: String): CrawlOutcome {
        Playwright.create().use { playwright ->
            val launchOptions = BrowserType.LaunchOptions().setHeadless(true)
            // Block service workers: a SW's fetches bypass context.route (so the SSRF guard would never
            // see a SW request to an internal IP) and they add crawl nondeterminism — §4.4 defense-in-depth.
            val contextOptions = Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK)
            playwright.chromium().launch(launchOptions).use { browser ->
                browser.newContext(contextOptions).use { context ->
                    val pageTimeoutMillis =
                        properties.scan.pageTimeout
                            .toMillis()
                            .toDouble()
                    context.setDefaultNavigationTimeout(pageTimeoutMillis)
                    // Bounds page.evaluate()/context.cookies() too — without it a hostile page could hang
                    // the worker inside DOM extraction, past the between-pages job budget (§4.4 hard cap).
                    context.setDefaultTimeout(pageTimeoutMillis)
                    context.route("**/*") { route -> guardRequest(route, domain) }
                    // context.route covers HTTP(S) only; a WebSocket handshake needs its own guard, or
                    // ws://<internal-ip>/ opened from page JS would reach a private range unchecked.
                    context.routeWebSocket("**/*") { wsRoute -> guardWebSocket(wsRoute) }
                    return CrawlOutcome(pagesCrawled = crawlPages(context, domain), cookies = context.cookies())
                }
            }
        }
    }

    /**
     * Breadth-first, same-host crawl starting at the homepage, bounded by `maxPages` and the per-job
     * time budget. The homepage is mandatory (its failure fails the scan); a deeper page that fails is
     * skipped so one bad link doesn't sink the whole crawl.
     */
    private fun crawlPages(
        context: BrowserContext,
        domain: String,
    ): Int {
        val start = clock.instant()
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        val root = "https://$domain/"

        val homepage = navigate(context, root, isHomepage = true) ?: return 0
        var pages = 1
        try {
            visited.add(root)
            enqueueSameHostLinks(homepage, domain, visited, queue)
        } finally {
            homepage.close()
        }

        while (queue.isNotEmpty() && pages < properties.scan.maxPages && withinJobBudget(start)) {
            val url = queue.removeFirst()
            // Count a page only when it's newly visited AND actually opened.
            if (visited.add(url) && visitPage(context, url, domain, visited, queue)) {
                pages++
            }
        }
        return pages
    }

    /** Open [url], harvest its same-host links, and close it. Returns false if the page never opened. */
    private fun visitPage(
        context: BrowserContext,
        url: String,
        domain: String,
        visited: Set<String>,
        queue: ArrayDeque<String>,
    ): Boolean {
        val page = navigate(context, url, isHomepage = false) ?: return false
        return try {
            enqueueSameHostLinks(page, domain, visited, queue)
            true
        } finally {
            page.close()
        }
    }

    /**
     * Open [url] in a fresh page. On the homepage a failure is mapped to a [ScanTargetException] and
     * propagates (the scan fails/retries); on a deeper page it is logged and null is returned so the
     * crawl continues. The returned page is left open for the caller to inspect then close.
     */
    private fun navigate(
        context: BrowserContext,
        url: String,
        isHomepage: Boolean,
    ): Page? {
        val page = context.newPage()
        val options = Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
        return try {
            page.navigate(url, options)
            page
        } catch (ex: TimeoutError) {
            page.close()
            if (isHomepage) throw ScanTargetException(ScanFailureReason.TIMEOUT, "homepage navigation timed out", ex)
            log.warn("Scan page {} timed out; skipping", url, ex)
            null
        } catch (ex: PlaywrightException) {
            page.close()
            if (isHomepage) throw ScanTargetException(ScanFailureReason.UNREACHABLE, "homepage unreachable", ex)
            log.warn("Scan page {} failed ({}); skipping", url, ex.message)
            null
        }
    }

    private fun enqueueSameHostLinks(
        page: Page,
        domain: String,
        visited: Set<String>,
        queue: ArrayDeque<String>,
    ) {
        // Enqueue same-host links until we've queued enough to reach the page cap. takeWhile re-checks
        // the growing queue each step, so we never buffer more than maxPages worth of URLs.
        extractHrefs(page)
            .asSequence()
            .mapNotNull { normalizeSameHost(it, domain) }
            .filter { it !in visited && it !in queue }
            .takeWhile { visited.size + queue.size < properties.scan.maxPages }
            .forEach { queue.add(it) }
    }

    private fun extractHrefs(page: Page): List<String> {
        val evaluated =
            runCatching {
                page.evaluate("() => Array.from(document.querySelectorAll('a[href]'), a => a.href)")
            }.getOrNull()
        return (evaluated as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }

    /** Keep only http(s) links on the same host family, fragment-stripped; null rejects the link. */
    private fun normalizeSameHost(
        href: String,
        domain: String,
    ): String? {
        val uri = runCatching { URI(href) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (!sameHostFamily(host, domain)) return null
        return runCatching { URI(uri.scheme, uri.authority, uri.path, uri.query, null).toString() }.getOrNull()
    }

    /**
     * The per-request SSRF guard installed on the browser context. Runs for every network request the
     * page makes; see the class docs for the navigation vs. sub-resource policy.
     */
    private fun guardRequest(
        route: Route,
        domain: String,
    ) {
        val request = route.request()
        val url = request.url()
        // Hostless, non-network schemes (inline data:/blob:, about:blank) can't reach the network and
        // are common on real pages — let them through rather than aborting and perturbing page load.
        if (isHostlessSafeScheme(url)) {
            route.resume()
            return
        }
        val host = hostOf(url)
        if (host == null) {
            route.abort()
            return
        }
        val isMainNavigation = request.isNavigationRequest && request.frame().parentFrame() == null
        val allowed =
            if (isMainNavigation) {
                sameHostFamily(host, domain) && validator.isPublicHost(host)
            } else {
                !validator.isDisallowedIpLiteral(host)
            }
        if (allowed) route.resume() else route.abort()
    }

    /**
     * SSRF guard for WebSocket handshakes, which [BrowserContext.route] does not intercept. Mirrors the
     * sub-resource policy: a public host is connected through (third parties legitimately use WS), a
     * private/reserved IP literal is refused. The DNS-free literal check keeps this off the resolver path.
     */
    private fun guardWebSocket(route: WebSocketRoute) {
        val host = hostOf(route.url())
        if (host != null && !validator.isDisallowedIpLiteral(host)) {
            route.connectToServer()
        } else {
            route.close()
        }
    }

    private fun withinJobBudget(start: Instant): Boolean = Duration.between(start, clock.instant()) < properties.scan.jobTimeout

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** data:/blob:/about: URLs carry no host and can't reach the network — safe to let through. */
    private fun isHostlessSafeScheme(url: String): Boolean {
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull() ?: return false
        return scheme in HOSTLESS_SAFE_SCHEMES
    }

    /** apex ↔ www and any sub-domain of the verified domain count as the same site. */
    private fun sameHostFamily(
        host: String,
        domain: String,
    ): Boolean {
        val h = host.lowercase()
        val d = domain.lowercase()
        return h == d || h == "www.$d" || d == "www.$h" || h.endsWith(".$d")
    }

    private companion object {
        val HOSTLESS_SAFE_SCHEMES = setOf("data", "blob", "about")
    }
}
