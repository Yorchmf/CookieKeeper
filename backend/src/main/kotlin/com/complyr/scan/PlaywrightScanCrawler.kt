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
 * the site must exist and be ACTIVE — then drives the shared [ScanEngine] over the site's domain and
 * classifies/persists the observed cookies into `scan_cookies`.
 *
 * Verification selects crawl *depth*, it does not gate crawling (docs ADR-17). An unverified site is
 * crawled in [CrawlMode.QUICK] — byte-for-byte the same posture as the anonymous funnel, which already
 * QUICK-crawls arbitrary unowned domains through this same engine — and a verified site gets the
 * multi-page [CrawlMode.FULL] depth the plan pays for. Refusing to crawl an unverified *registered*
 * site while happily crawling an unverified *stranger's* domain bought no safety, only a dead end:
 * every scan a customer ever enqueued failed, because nothing set `verified_at`.
 *
 * SSRF defense is therefore entirely the engine's: [ScanTargetValidator] plus the per-request route
 * guards plus container network isolation, identical for both paths. The crawl itself (browser, time
 * budgets) lives in the engine; this class is deliberately thin so both callers share it.
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
        // Verification buys depth, not permission to crawl (ADR-17): unverified sites get the same
        // single-page pass the anonymous funnel runs, verified ones get the paid multi-page crawl.
        val mode = if (site.verifiedAt != null) CrawlMode.FULL else CrawlMode.QUICK

        val outcome = engine.crawl(site.domain, mode)
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
