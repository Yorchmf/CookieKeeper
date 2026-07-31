package com.complyr.scan

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Outcome of crawling one site: what the queue needs to record a successful scan. */
data class ScanCrawlResult(
    val pagesCrawled: Int,
)

/**
 * Performs the actual site crawl for a claimed scan. Kept behind an interface so the queue/worker
 * plumbing (slice 1) is testable and reviewable without a browser, and the Playwright-for-Java
 * implementation (slice 2) drops in without touching [ScanWorker].
 */
interface ScanCrawler {
    /** Crawl the claimed scan's site and return its result. Throwing marks the scan failed/retried. */
    fun crawl(claim: ClaimedScan): ScanCrawlResult
}

/**
 * Placeholder crawler for slice 1: records a zero-page result so the queue lifecycle (enqueue ->
 * claim -> succeed) is exercisable end to end before any browser exists.
 *
 * TODO(W4 slice 2): replace with the SSRF-hardened Playwright crawler — resolve the site's DNS and
 *  reject private/link-local ranges, refuse to crawl an unverified domain (`site.verifiedAt`), cap
 *  pages/time per §4.4, and collect cookies/localStorage/third-party hosts into `scan_cookies`.
 */
@Component
class NoopScanCrawler : ScanCrawler {
    private val log = LoggerFactory.getLogger(NoopScanCrawler::class.java)

    override fun crawl(claim: ClaimedScan): ScanCrawlResult {
        log.info("Stub crawl for scan {} (site {}) — no browser yet (W4 slice 2)", claim.scanId, claim.siteId)
        return ScanCrawlResult(pagesCrawled = 0)
    }
}
