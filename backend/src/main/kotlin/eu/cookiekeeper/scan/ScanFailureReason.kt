package eu.cookiekeeper.scan

/**
 * Closed set of customer-safe scan failure reasons, stored in `scans.error` (and mirrored to
 * `jobs.last_error`) instead of a raw exception message. Raw throwables — which carry internal
 * hostnames, resolved IPs, and stack detail — stay in the server logs only, so nothing internal
 * leaks into the dashboard or the audit row (GDPR: we must be exemplary).
 *
 * Codes are stable, machine-readable tokens the dashboard maps to localized copy (i18n from day one);
 * they are never human sentences.
 */
enum class ScanFailureReason(
    val code: String,
) {
    /** Catch-all: an unexpected server-side error the customer can do nothing about. */
    INTERNAL("internal_error"),

    /**
     * No longer produced. Verification once gated crawling outright; since ADR-17 it only selects
     * crawl depth ([CrawlMode.QUICK] vs [CrawlMode.FULL]), so nothing throws this. Retained because
     * historical `scans.error` rows still carry the code and the dashboard must keep rendering them.
     */
    DOMAIN_NOT_VERIFIED("domain_not_verified"),

    /** The domain resolves to a private/reserved/link-local address and was refused (SSRF block). */
    BLOCKED_TARGET("blocked_target"),

    /** The domain does not resolve in DNS. */
    DNS_FAILURE("dns_failure"),

    /** A page navigation (or the whole crawl) exceeded its time budget (§4.4). */
    TIMEOUT("timeout"),

    /** The site could not be reached / the browser failed to load it. */
    UNREACHABLE("unreachable"),
}
