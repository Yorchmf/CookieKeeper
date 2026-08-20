package eu.cookiekeeper.analytics.dto

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
    // The prior window (same length, immediately before [range]) for period-over-period deltas; null when
    // no comparable baseline exists — see [PeriodSummary].
    val previous: PeriodSummary?,
    // Null until the site has a completed scan / published policy — the dashboard shows an empty state.
    val cookies: CookieAnalytics?,
    val policy: PolicyAnalytics?,
    // Present only when a consent re-prompt landed inside [range] — see [ConsentRepromptNotice].
    val reprompt: ConsentRepromptNotice?,
)

/**
 * A consent re-prompt that happened inside the displayed window (BACKLOG #18): the site started using a
 * category its stored consents never covered, so the widget asked visitors again.
 *
 * This exists because the re-prompt is *our* effect on *their* numbers. A re-prompt wave re-shows the banner
 * to visitors who already had a valid choice, so impressions jump and the interaction rate steps — a
 * discontinuity the customer would otherwise read as a change in their traffic. The dashboard says so at the
 * point where the step appears.
 */
data class ConsentRepromptNotice(
    val changedAt: Instant,
    // The categories newly in use that triggered it, e.g. ["marketing"]; empty only for rows written before
    // the reason was recorded.
    val addedCategories: List<String>,
)

/** The resolved window the figures cover ([from] inclusive, [to] exclusive). */
data class AnalyticsRange(
    val from: Instant,
    val to: Instant,
)

data class ConsentAnalytics(
    val totalEvents: Long,
    val byAction: ActionBreakdown,
    // How many times the banner was shown over the window (Track 4 Slice D), from the `banner_impressions`
    // counter — the denominator behind [interactionRate]. 0 for a window with no recorded impressions
    // (e.g. before the beacon shipped, or after the impression retention window has pruned those days).
    val impressions: Long,
    // Fraction of banner impressions that produced a consent decision: [totalEvents] / [impressions], and
    // 0.0 (not a division-by-zero) when [impressions] is 0. Can exceed 1.0 at the edges — a decision can be
    // recorded without a fresh impression (re-consent on a page that didn't re-show the banner), and the two
    // series have different retention windows — so the dashboard treats it as an indicator, not an invariant.
    val interactionRate: Double,
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

/**
 * A lean consent baseline for the window immediately preceding the one on display — just the totals a
 * period-over-period delta needs ([totalEvents] and the [byAction] mix), not the full trend/category/language
 * detail. Present on a response only when a *comparable* prior window exists: the same length as the current
 * window and sitting entirely at or above the plan retention floor (ADR-16). When it would be truncated or
 * clipped by retention it is omitted (null) rather than compared against, so the dashboard never shows a delta
 * skewed by a shorter or partly-unreadable baseline.
 */
data class PeriodSummary(
    val totalEvents: Long,
    val byAction: ActionBreakdown,
    // Banner impressions over the prior window (Track 4 Slice D), so the dashboard can show a
    // period-over-period delta on the impression count and the interaction rate alongside the consent
    // deltas. Same 0-when-none / can-exceed semantics as [ConsentAnalytics.impressions].
    val impressions: Long,
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
 * Cookie inventory from the site's most recent completed scan. [insecure] mirrors [eu.cookiekeeper.scan.ComplianceAnalyzer]
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
