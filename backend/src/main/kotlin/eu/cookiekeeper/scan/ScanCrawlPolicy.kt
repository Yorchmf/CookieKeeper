package eu.cookiekeeper.scan

import java.net.URI

/**
 * The pure, browser-free decisions the crawl makes about *where* it may go — extracted from the
 * Playwright engine so the same-host and link-normalization rules (which are SSRF-adjacent) are
 * unit-testable without launching Chromium. The engine layers the DNS/range checks
 * ([ScanTargetValidator]) and the network-isolation backstop on top of these.
 */
object ScanCrawlPolicy {
    /**
     * How many pages a crawl may open in [mode]. QUICK is the anonymous funnel's homepage-only pass
     * (one page, no link following); FULL is the authenticated multi-page crawl bounded by the
     * configured `cookiekeeper.scan.max-pages`.
     */
    fun effectiveMaxPages(
        mode: CrawlMode,
        configuredMaxPages: Int,
    ): Int =
        when (mode) {
            CrawlMode.QUICK -> QUICK_MODE_MAX_PAGES
            CrawlMode.FULL -> configuredMaxPages
        }

    /** Lower-cased host of [url], or null if it has none (parse failure or a hostless scheme). */
    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /** data:/blob:/about: URLs carry no host and can't reach the network — safe to let through. */
    fun isHostlessSafeScheme(url: String): Boolean {
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull() ?: return false
        return scheme in HOSTLESS_SAFE_SCHEMES
    }

    /** apex ↔ www and any sub-domain of the crawl [domain] count as the same site. */
    fun sameHostFamily(
        host: String,
        domain: String,
    ): Boolean {
        val h = host.lowercase()
        val d = domain.lowercase()
        return h == d || h == "www.$d" || d == "www.$h" || h.endsWith(".$d")
    }

    /** Keep only http(s) links on the same host family, fragment-stripped; null rejects the link. */
    fun normalizeSameHost(
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

    private const val QUICK_MODE_MAX_PAGES = 1
    private val HOSTLESS_SAFE_SCHEMES = setOf("data", "blob", "about")
}
