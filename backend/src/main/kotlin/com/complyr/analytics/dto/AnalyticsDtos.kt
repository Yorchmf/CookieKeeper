package com.complyr.analytics.dto

import org.springframework.format.annotation.DateTimeFormat
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Inbound query filters for the site analytics read, bound from the query string as a command object. Both
 * bounds are optional and the service resolves defaults ([to] = now, [from] = to − default window); [from] is
 * inclusive and [to] exclusive, matching the consent-log convention so day/month buckets compose cleanly.
 */
data class AnalyticsFilter(
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val from: Instant? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val to: Instant? = null,
)

/**
 * The customer-facing analytics for one site over a resolved time window: how visitors consented, what the
 * latest scan found in the cookie inventory, and the current published policy version. All data is our own —
 * aggregated from `consent_events`, `scan_cookies`/`scans`, and `policies`; no third-party telemetry.
 */
data class SiteAnalyticsResponse(
    val range: AnalyticsRange,
    val consent: ConsentAnalytics,
    // Null until the site has a completed scan / published policy — the dashboard shows an empty state.
    val cookies: CookieAnalytics?,
    val policy: PolicyAnalytics?,
)

/** The resolved window the figures cover ([from] inclusive, [to] exclusive). */
data class AnalyticsRange(
    val from: Instant,
    val to: Instant,
)

data class ConsentAnalytics(
    val totalEvents: Long,
    val byAction: ActionBreakdown,
    val trend: List<ConsentTrendPoint>,
    val categoryOptIn: List<CategoryOptIn>,
    val languageSplit: List<LanguageCount>,
)

/** Consent decision mix over the window. Keys mirror the `ck_consent_events_action` DB check. */
data class ActionBreakdown(
    val acceptAll: Long,
    val rejectAll: Long,
    val custom: Long,
)

/** One UTC day of the consent trend; [total] = [acceptAll] + [rejectAll] + [custom]. */
data class ConsentTrendPoint(
    val date: LocalDate,
    val acceptAll: Long,
    val rejectAll: Long,
    val custom: Long,
    val total: Long,
)

/**
 * Opt-in signal for one consent [category] over the window: [optIns] events set it true out of [decisions]
 * events that carried the category at all, with [rate] the ratio (0.0 when [decisions] is 0).
 */
data class CategoryOptIn(
    val category: String,
    val optIns: Long,
    val decisions: Long,
    val rate: Double,
)

data class LanguageCount(
    val lang: String,
    val count: Long,
)

/**
 * Cookie inventory from the site's most recent completed scan. [insecure] mirrors [com.complyr.scan.ComplianceAnalyzer]
 * (non-necessary classified cookies missing both Secure and HttpOnly); [trackerCount] is the scan's distinct
 * marketing-tracker count. [scannedAt] is when the crawl finished.
 */
data class CookieAnalytics(
    val scanId: UUID,
    val scannedAt: Instant,
    val total: Int,
    val byCategory: List<CategoryCount>,
    val known: Int,
    val unknown: Int,
    val insecure: Int,
    val trackerCount: Int,
)

/** Cookie count for one category bucket (the four taxonomy categories plus an `unclassified` bucket). */
data class CategoryCount(
    val category: String,
    val count: Int,
)

/** The current published cookie policy: its [version], when it was published, and the languages it covers. */
data class PolicyAnalytics(
    val version: Int,
    val publishedAt: Instant?,
    val languages: List<String>,
)
