package eu.cookiekeeper.scan

/**
 * Performs the actual crawl for a claimed anonymous scan — the funnel twin of [ScanCrawler]. Kept
 * behind an interface for the same reason: the worker's public branch is then testable with a fake,
 * and the Playwright-for-Java implementation ([PlaywrightPublicScanCrawler], `scanner` profile only)
 * is the sole production binding.
 *
 * A thrown [ScanTargetException] carries a customer-safe reason code; any other throwable is recorded
 * as an internal error. Either way the worker marks the scan failed (see [ScanWorker]).
 */
interface PublicScanCrawler {
    /** Crawl the claimed anonymous scan's domain and return its result. Throwing marks the scan failed. */
    fun crawl(claim: ClaimedPublicScan): ScanCrawlResult
}
