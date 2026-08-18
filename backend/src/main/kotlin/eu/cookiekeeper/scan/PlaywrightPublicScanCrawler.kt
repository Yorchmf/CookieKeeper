package eu.cookiekeeper.scan

import com.microsoft.playwright.options.Cookie
import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The anonymous free-scan adapter (`scanner` profile) — the funnel twin of [PlaywrightScanCrawler].
 * It drives the shared [ScanEngine] over a visitor-supplied domain in the cheap single-page
 * [CrawlMode.QUICK] mode, classifies the observed cookies, and persists them into `public_scan_cookies`.
 *
 * Crucially there is **no ownership/verified gate here** (docs ADR-12): the domain is not a customer's
 * proven property, so we cannot require verification. The load-bearing SSRF defense is
 * [ScanTargetValidator] (run inside the engine before any browser launches) plus the scanner
 * container's network isolation — this adapter deliberately does not add its own gate, only the same
 * classify/cap/persist pipeline the authenticated path uses.
 */
@Component
@Profile("scanner")
class PlaywrightPublicScanCrawler(
    private val cookieWriter: PublicScanCookieWriter,
    private val classifier: CookieClassifier,
    private val trackerClassifier: TrackerClassifier,
    private val engine: ScanEngine,
    private val properties: CookieKeeperProperties,
) : PublicScanCrawler {
    private val log = LoggerFactory.getLogger(PlaywrightPublicScanCrawler::class.java)

    override fun crawl(claim: ClaimedPublicScan): ScanCrawlResult {
        val outcome = engine.crawl(claim.domain, CrawlMode.QUICK)
        val recorded = persistCookies(claim.publicScanId, outcome.cookies)
        val marketingTrackers = trackerClassifier.countMarketingTrackers(outcome.thirdPartyHosts)
        // Count only — never the crawled domain's cookie names or tracker hosts (§4 no attacker data in logs).
        log.info(
            "Public scan {} crawled {} page(s), recorded {} cookie(s), {} marketing tracker(s)",
            claim.publicScanId,
            outcome.pagesCrawled,
            recorded,
            marketingTrackers,
        )
        return ScanCrawlResult(pagesCrawled = outcome.pagesCrawled, marketingTrackerCount = marketingTrackers)
    }

    /**
     * Map → classify → project → persist. Reuses [ScanCookieMapper] + [CookieClassifier] on
     * [ScanCookieEntity] intermediates (the classifier reads only the cookie name, so the FK identity is
     * irrelevant to it), then projects each classified row onto a [PublicScanCookieEntity] carrying the
     * `public_scan_id`. Returns how many rows were recorded (post de-dup/cap).
     */
    private fun persistCookies(
        publicScanId: UUID,
        cookies: List<Cookie>,
    ): Int {
        val caps = ScanCookieMapper.Caps(properties.scan.maxCookies, properties.scan.maxCookieNameLength)
        // The mapper keys rows by scanId; for the public path the id IS the public_scan_id — nothing
        // reads it back off the intermediate rows, we re-key on projection below.
        val mapped = ScanCookieMapper.toEntities(publicScanId, cookies, caps)
        if (mapped.wasCapped) {
            log.warn(
                "Public scan {} hit the cookie cap ({}); excess observed cookies were not recorded",
                publicScanId,
                caps.maxCookies,
            )
        }
        val classified = classifier.classify(mapped.rows)
        cookieWriter.replace(publicScanId, classified.map { it.toPublicRow(publicScanId) })
        return mapped.rows.size
    }

    /** Project a classified `scan_cookies` row onto its `public_scan_cookies` twin (same fields, new FK). */
    private fun ScanCookieEntity.toPublicRow(publicScanId: UUID): PublicScanCookieEntity =
        PublicScanCookieEntity(
            publicScanId = publicScanId,
            name = name,
            domain = domain,
            expiry = expiry,
            category = category,
            provider = provider,
            isKnown = isKnown,
            secure = secure,
            httpOnly = httpOnly,
        )
}
