package eu.cookiekeeper.scan

import com.microsoft.playwright.options.Cookie

/** How deep a crawl goes. See [ScanCrawlPolicy.effectiveMaxPages]. */
enum class CrawlMode {
    /** Anonymous funnel: the homepage only, one page, no link following (fast, cheap). */
    QUICK,

    /** Authenticated scan: the full multi-page same-host crawl bounded by `cookiekeeper.scan.max-pages`. */
    FULL,
}

/**
 * Raw output of crawling a bare domain: how many pages opened, the cookies observed, and the distinct
 * third-party request hosts seen ([thirdPartyHosts]).
 *
 * [cookies] is Playwright's own [Cookie] type — a deliberate coupling, not a leak to paper over: the
 * sole production engine is Playwright and [ScanCookieMapper] already consumes [Cookie] directly, so
 * an intermediate DTO would buy nothing today (YAGNI) while forcing every fake/alternative engine to
 * fabricate Playwright objects. If a genuinely non-Playwright engine ever lands, introduce an
 * `ObservedCookie` value type here and map at each engine edge.
 *
 * [thirdPartyHosts] are the off-site hosts the page issued requests to (host lower-cased, never a
 * same-host-family or private-range host, bounded to a cap so a hostile page can't unbound the set).
 * They are transient crawl telemetry only — the caller ([PlaywrightScanCrawler] via [TrackerClassifier])
 * derives a marketing-tracker *count* from them and nothing else; the raw hosts are never persisted.
 */
data class EngineCrawlResult(
    val pagesCrawled: Int,
    val cookies: List<Cookie>,
    val thirdPartyHosts: Set<String>,
)

/**
 * The reusable crawl engine: load a bare [domain] in its before-consent state and return the cookies
 * it dropped, independent of *whose* scan it is. Both the authenticated per-site crawler and the
 * anonymous funnel drive the same engine — they differ only in the [CrawlMode] and in how the result
 * is classified and stored, not in how the browser crawls.
 *
 * Kept behind an interface (like [ScanCrawler]) so the orchestration around it — site verification,
 * classification, persistence — is unit-testable with a fake engine, no browser required. The
 * Playwright implementation ([PlaywrightScanEngine], `scanner` profile only) is the sole production
 * binding and owns the SSRF pre-flight + per-request guards.
 */
interface ScanEngine {
    /**
     * Crawl [domain] in [mode] and return the observed cookies. Throws [ScanTargetException] with a
     * customer-safe reason when the target is refused (non-public / unresolvable) or the crawl times
     * out / is unreachable.
     */
    fun crawl(
        domain: String,
        mode: CrawlMode,
    ): EngineCrawlResult
}
