package com.complyr.scan

/** Outcome of crawling one site: what the queue needs to record a successful scan. */
data class ScanCrawlResult(
    val pagesCrawled: Int,
)

/**
 * Performs the actual site crawl for a claimed scan. Kept behind an interface so the queue/worker
 * plumbing is testable and reviewable without a browser; the Playwright-for-Java implementation
 * ([PlaywrightScanCrawler], `scanner` profile only) is the sole production binding.
 *
 * A thrown [ScanTargetException] carries a customer-safe reason code; any other throwable is
 * recorded as an internal error. Either way the worker marks the scan failed/retried (see
 * [ScanWorker]).
 */
interface ScanCrawler {
    /** Crawl the claimed scan's site and return its result. Throwing marks the scan failed/retried. */
    fun crawl(claim: ClaimedScan): ScanCrawlResult
}
