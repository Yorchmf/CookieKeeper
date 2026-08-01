package com.complyr.scan

import com.complyr.common.ComplyrProperties
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import com.microsoft.playwright.options.Cookie
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The authenticated per-site scan adapter (`scanner` profile). It owns the ownership/state gates —
 * the site must exist, be ACTIVE, and be *verified* (§4.4 SSRF posture: we only crawl a domain the
 * customer proved they control) — then drives the shared [ScanEngine] over the site's domain in the
 * full multi-page mode and classifies/persists the observed cookies into `scan_cookies`.
 *
 * The crawl itself (browser, SSRF pre-flight + per-request guards, time budgets) lives in the engine;
 * this class is deliberately thin so the anonymous funnel (docs ADR-12) can reuse the same engine
 * without inheriting the verified-domain requirement.
 */
@Component
@Profile("scanner")
class PlaywrightScanCrawler(
    private val siteRepository: SiteRepository,
    private val cookieWriter: ScanCookieWriter,
    private val classifier: CookieClassifier,
    private val engine: ScanEngine,
    private val properties: ComplyrProperties,
) : ScanCrawler {
    private val log = LoggerFactory.getLogger(PlaywrightScanCrawler::class.java)

    override fun crawl(claim: ClaimedScan): ScanCrawlResult {
        val site =
            siteRepository.findById(claim.siteId).orElseThrow {
                ScanTargetException(ScanFailureReason.INTERNAL, "scan ${claim.scanId}: site ${claim.siteId} not found")
            }
        if (site.status != SiteStatus.ACTIVE) {
            throw ScanTargetException(ScanFailureReason.INTERNAL, "scan ${claim.scanId}: site ${site.id} is not active")
        }
        // SSRF posture (§4.4): only ever crawl a domain the customer has proven they control. The
        // engine adds the resolve-public pre-flight + per-request guards on top.
        if (site.verifiedAt == null) {
            throw ScanTargetException(ScanFailureReason.DOMAIN_NOT_VERIFIED, "scan ${claim.scanId}: domain not verified")
        }

        val outcome = engine.crawl(site.domain, CrawlMode.FULL)
        val recorded = persistCookies(claim.scanId, outcome.cookies)
        // Log the count actually persisted after de-dup/cap, not the raw observed count.
        log.info("Scan {} crawled {} page(s), recorded {} cookie(s)", claim.scanId, outcome.pagesCrawled, recorded)
        return ScanCrawlResult(pagesCrawled = outcome.pagesCrawled)
    }

    /** Persists the classified cookie rows and returns how many were recorded (post de-dup/cap). */
    private fun persistCookies(
        scanId: UUID,
        cookies: List<Cookie>,
    ): Int {
        // Map -> classify against the signature DB -> persist. A retry reuses the same scan id, so the
        // writer clears the prior attempt's findings before re-recording — atomically, in one
        // transaction (findings are replaceable, not audit evidence).
        val caps = ScanCookieMapper.Caps(properties.scan.maxCookies, properties.scan.maxCookieNameLength)
        val mapped = ScanCookieMapper.toEntities(scanId, cookies, caps)
        if (mapped.wasCapped) {
            // Count only — never log attacker-controlled cookie names (§4 no-PII/no-injection in logs).
            log.warn("Scan {} hit the cookie cap ({}); excess observed cookies were not recorded", scanId, caps.maxCookies)
        }
        val classified = classifier.classify(mapped.rows)
        cookieWriter.replace(scanId, classified)
        return mapped.rows.size
    }
}
