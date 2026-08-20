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
 * What the crawl could tell about the Complyr embed on the crawled pages — the backing signal for the
 * post-install blocking verification (BACKLOG #19).
 *
 * Deliberately booleans and a count, nothing else. The probe runs *inside* the crawled page, which is
 * attacker-influenced content, so the site-key comparison happens in the browser against the key we
 * pass in and only its verdict crosses back (§4: no attacker-controlled string is carried, logged or
 * stored). There is no "observed key" field on purpose.
 *
 * [installed] is true when any crawled page carried our embed (`script[data-complyr]`) or exposed the
 * `window.Complyr` global — the latter catches installs injected by a tag manager, where no static tag
 * exists in the served HTML. [siteKeyMatched] is true only when an embed carried the key of the site
 * being scanned; it is always false for the anonymous funnel, which has no site to match against.
 * [blockedScriptCount] is how many `text/plain` placeholders the owner had already tagged.
 */
data class WidgetProbe(
    val installed: Boolean,
    val siteKeyMatched: Boolean,
    val blockedScriptCount: Int,
) {
    companion object {
        /** No embed seen: the state of a page that never loaded, and the neutral default for fakes. */
        val ABSENT = WidgetProbe(installed = false, siteKeyMatched = false, blockedScriptCount = 0)
    }

    /** Fold another page's probe into this one: installed/matched are ORs, the count is the page maximum. */
    fun merge(other: WidgetProbe): WidgetProbe =
        WidgetProbe(
            installed = installed || other.installed,
            siteKeyMatched = siteKeyMatched || other.siteKeyMatched,
            blockedScriptCount = maxOf(blockedScriptCount, other.blockedScriptCount),
        )
}

/**
 * Raw output of crawling a bare domain: how many pages opened, the cookies observed, the distinct
 * third-party request hosts seen ([thirdPartyHosts]), and what the pages said about our own embed
 * ([widget]).
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
 * reduces them to a *count* and to matched dataset keys, and nothing else; the raw hosts are never
 * persisted.
 */
data class EngineCrawlResult(
    val pagesCrawled: Int,
    val cookies: List<Cookie>,
    val thirdPartyHosts: Set<String>,
    val widget: WidgetProbe = WidgetProbe.ABSENT,
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
     *
     * [expectedSiteKey] is the public site key the crawled pages *should* be embedding, used only for
     * an in-page equality check whose boolean result lands on [EngineCrawlResult.widget]. Null for the
     * anonymous funnel, where there is no registered site to match against.
     */
    fun crawl(
        domain: String,
        mode: CrawlMode,
        expectedSiteKey: String? = null,
    ): EngineCrawlResult
}
