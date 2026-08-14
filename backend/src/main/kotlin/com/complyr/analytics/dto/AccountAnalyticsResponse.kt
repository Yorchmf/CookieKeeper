package com.complyr.analytics.dto

/**
 * Cross-site consent roll-up (Track 4 Slice A): the same [ConsentAnalytics] shape a single site returns
 * ([SiteAnalyticsResponse.consent]), aggregated over every ACTIVE site the account owns, plus [siteCount] —
 * how many sites the figures cover, surfaced as the dashboard's "Active sites: N" label.
 *
 * Cookie- and policy-inventory roll-ups are intentionally out of Slice A (consent only); they can join this
 * response later without breaking the shape.
 */
data class AccountAnalyticsResponse(
    val range: AnalyticsRange,
    val consent: ConsentAnalytics,
    val siteCount: Int,
)
