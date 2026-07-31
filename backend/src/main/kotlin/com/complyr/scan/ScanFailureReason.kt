package com.complyr.scan

/**
 * Closed set of customer-safe scan failure reasons, stored in `scans.error` (and mirrored to
 * `jobs.last_error`) instead of a raw exception message. Raw throwables — which, once Slice 2 wires a
 * real crawler, will carry internal hostnames, IPs, and stack detail — stay in the server logs only, so
 * nothing internal leaks into the dashboard or the audit row (GDPR: we must be exemplary).
 *
 * Slice 1's crawler is a no-op, so [INTERNAL] is the only outcome. Slice 2 adds concrete, classifiable
 * reasons (e.g. timeout, DNS failure, unreachable host) as it introduces the crawl error types.
 */
enum class ScanFailureReason(
    val code: String,
) {
    INTERNAL("internal_error"),
}
