package eu.cookiekeeper.scan

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.Route
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.WebSocketRoute
import com.microsoft.playwright.options.ServiceWorkerPolicy
import com.microsoft.playwright.options.WaitUntilState
import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The production crawl engine (ARCHITECTURE ADR-5): a headless Chromium via Playwright-for-Java, run
 * only under the `scanner` Spring profile (the browser binaries exist solely in the scanner container).
 *
 * It loads a bare domain in its *before-consent* state — no consent is given, so every cookie/tracker
 * seen is one the site drops without permission — and returns the observed cookies. Who owns the scan,
 * how the cookies are classified, and where they are stored are the caller's concern (see
 * [PlaywrightScanCrawler] for the authenticated per-site path); the engine only crawls.
 *
 * SSRF hardening (§4.4) is layered and applies to every crawl, owned or anonymous:
 *  1. [ScanTargetValidator.validate] up front — the domain must resolve to a public address.
 *  2. A per-request route guard: main-document navigations must stay on the target host family and
 *     resolve public (blocks off-origin redirects and DNS-rebinding into internal ranges); sub-resources
 *     may reach public third parties (we must observe trackers) but never a private IP literal.
 *  3. Hard per-page and per-job time budgets so a hostile or broken site cannot hang a worker.
 *
 * No caller layers an ownership check on top: both the authenticated path and the anonymous funnel
 * crawl unverified domains (verification selects depth only — docs ADR-12, ADR-17), so [validate] plus
 * the per-request guards plus the network-isolation backstop are the *sole* SSRF defense for every
 * crawl. Nothing here may assume the target is a domain the caller controls.
 */
@Component
@Profile("scanner")
class PlaywrightScanEngine(
    private val validator: ScanTargetValidator,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
) : ScanEngine {
    private val log = LoggerFactory.getLogger(PlaywrightScanEngine::class.java)

    override fun crawl(
        domain: String,
        mode: CrawlMode,
    ): EngineCrawlResult {
        // Fail closed before launching a browser if the domain resolves anywhere non-public.
        validator.validate(domain)
        val maxPages = ScanCrawlPolicy.effectiveMaxPages(mode, properties.scan.maxPages)
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
                    // Distinct off-site request hosts observed during the crawl — transient telemetry the
                    // caller turns into a marketing-tracker count. Concurrent because Playwright fires route
                    // callbacks off its own dispatcher; bounded (see [guardRequest]) so a hostile page that
                    // fans out to thousands of hosts can't grow this without limit.
                    val thirdPartyHosts = ConcurrentHashMap.newKeySet<String>()
                    context.route("**/*") { route -> guardRequest(route, domain, thirdPartyHosts) }
                    // context.route covers HTTP(S) only; a WebSocket handshake needs its own guard, or
                    // ws://<internal-ip>/ opened from page JS would reach a private range unchecked.
                    context.routeWebSocket("**/*") { wsRoute -> guardWebSocket(wsRoute) }
                    val pages = crawlPages(context, domain, maxPages)
                    return EngineCrawlResult(
                        pagesCrawled = pages,
                        cookies = context.cookies(),
                        thirdPartyHosts = thirdPartyHosts.toSet(),
                    )
                }
            }
        }
    }

    /**
     * The state of one breadth-first, same-host crawl: the immutable target ([context], [domain],
     * [maxPages]) plus the mutable frontier. Bundled so the traversal helpers stay within a sane
     * parameter count and can't drift out of sync on the visited/queue pair.
     */
    private class Traversal(
        val context: BrowserContext,
        val domain: String,
        val maxPages: Int,
    ) {
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
    }

    /**
     * Breadth-first, same-host crawl starting at the homepage, bounded by [Traversal.maxPages] and the
     * per-job time budget. The homepage is mandatory (its failure fails the scan); a deeper page that
     * fails is skipped so one bad link doesn't sink the whole crawl. In QUICK mode maxPages is 1, so the
     * loop never runs and only the homepage is opened.
     */
    private fun crawlPages(
        context: BrowserContext,
        domain: String,
        maxPages: Int,
    ): Int {
        val start = clock.instant()
        val crawl = Traversal(context, domain, maxPages)
        val root = "https://$domain/"

        val homepage = navigate(crawl.context, root, isHomepage = true) ?: return 0
        var pages = 1
        try {
            crawl.visited.add(root)
            enqueueSameHostLinks(crawl, homepage)
        } finally {
            homepage.close()
        }

        while (crawl.queue.isNotEmpty() && pages < maxPages && withinJobBudget(start)) {
            val url = crawl.queue.removeFirst()
            // Count a page only when it's newly visited AND actually opened.
            if (crawl.visited.add(url) && visitPage(crawl, url)) {
                pages++
            }
        }
        return pages
    }

    /** Open [url], harvest its same-host links, and close it. Returns false if the page never opened. */
    private fun visitPage(
        crawl: Traversal,
        url: String,
    ): Boolean {
        val page = navigate(crawl.context, url, isHomepage = false) ?: return false
        return try {
            enqueueSameHostLinks(crawl, page)
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
        crawl: Traversal,
        page: Page,
    ) {
        // Enqueue same-host links until we've queued enough to reach the page cap. takeWhile re-checks
        // the growing queue each step, so we never buffer more than maxPages worth of URLs.
        extractHrefs(page)
            .asSequence()
            .mapNotNull { ScanCrawlPolicy.normalizeSameHost(it, crawl.domain) }
            .filter { it !in crawl.visited && it !in crawl.queue }
            .takeWhile { crawl.visited.size + crawl.queue.size < crawl.maxPages }
            .forEach { crawl.queue.add(it) }
    }

    private fun extractHrefs(page: Page): List<String> {
        val evaluated =
            runCatching {
                page.evaluate("() => Array.from(document.querySelectorAll('a[href]'), a => a.href)")
            }.getOrNull()
        return (evaluated as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }

    /**
     * The per-request SSRF guard installed on the browser context. Runs for every network request the
     * page makes; see the class docs for the navigation vs. sub-resource policy. As a side effect it
     * records the distinct off-site hosts a request was *allowed* to reach into [thirdPartyHosts] — this
     * is only crawl telemetry (the caller derives a tracker count) and never changes the allow/deny
     * decision, which stays governed solely by the SSRF checks below.
     */
    private fun guardRequest(
        route: Route,
        domain: String,
        thirdPartyHosts: MutableSet<String>,
    ) {
        val request = route.request()
        val url = request.url()
        // Hostless, non-network schemes (inline data:/blob:, about:blank) can't reach the network and
        // are common on real pages — let them through rather than aborting and perturbing page load.
        if (ScanCrawlPolicy.isHostlessSafeScheme(url)) {
            route.resume()
            return
        }
        val host = ScanCrawlPolicy.hostOf(url)
        if (host == null) {
            route.abort()
            return
        }
        val isMainNavigation = request.isNavigationRequest && request.frame().parentFrame() == null
        val allowed =
            if (isMainNavigation) {
                ScanCrawlPolicy.sameHostFamily(host, domain) && validator.isPublicHost(host)
            } else {
                !validator.isDisallowedIpLiteral(host)
            }
        if (allowed) {
            recordThirdPartyHost(host, domain, thirdPartyHosts)
            route.resume()
        } else {
            route.abort()
        }
    }

    /**
     * Record an *allowed* request's host as third-party telemetry when it is off the crawl's own host
     * family, capping the set so a page that fans out to thousands of distinct hosts cannot grow it
     * unbounded. First-party (same-host-family) hosts are ignored — only off-site trackers matter here.
     */
    private fun recordThirdPartyHost(
        host: String,
        domain: String,
        thirdPartyHosts: MutableSet<String>,
    ) {
        if (ScanCrawlPolicy.sameHostFamily(host, domain)) return
        if (host.length > MAX_HOST_LENGTH) return
        if (thirdPartyHosts.size >= MAX_THIRD_PARTY_HOSTS) return
        thirdPartyHosts.add(host)
    }

    /**
     * SSRF guard for WebSocket handshakes, which [BrowserContext.route] does not intercept. Mirrors the
     * sub-resource policy: a public host is connected through (third parties legitimately use WS), a
     * private/reserved IP literal is refused. The DNS-free literal check keeps this off the resolver path.
     */
    private fun guardWebSocket(route: WebSocketRoute) {
        val host = ScanCrawlPolicy.hostOf(route.url())
        if (host != null && !validator.isDisallowedIpLiteral(host)) {
            route.connectToServer()
        } else {
            route.close()
        }
    }

    private fun withinJobBudget(start: Instant): Boolean = Duration.between(start, clock.instant()) < properties.scan.jobTimeout

    private companion object {
        // Bounds the transient third-party host set: a legitimate page touches a handful of trackers,
        // so this only trips for a pathological/hostile fan-out. A soft cap — a benign race on the
        // size check can overshoot by a few, which is harmless for telemetry.
        const val MAX_THIRD_PARTY_HOSTS = 500

        // Per-host length cap (defense-in-depth): a real hostname maxes at 253 chars, so anything longer
        // is malformed/hostile and cannot be a real tracker — drop it before it enters the set rather than
        // trust the browser to bound the string. Keeps the attacker-influenced host confined and small.
        const val MAX_HOST_LENGTH = 253
    }
}
