package com.complyr.scan.dto

import com.complyr.scan.ComplianceAnalyzer
import com.complyr.scan.PublicScanCookieEntity
import com.complyr.scan.PublicScanEntity
import com.complyr.scan.ScanStatus
import java.time.Instant

/**
 * The coarse, ungated "teaser" of an anonymous scan: enough to prove there is something worth seeing
 * (how many cookies, split across categories) without disclosing the cookie-level detail — names,
 * providers, expiries — which is the email-gated payload ([PublicScanReportResponse]).
 *
 * [verdict] is null until the scan reaches [ScanStatus.DONE]; while `queued`/`running` the caller
 * polls on [status] alone, and a `failed` scan carries a null verdict and no internal error string
 * (the public funnel never surfaces crawl-internal reasons).
 */
data class PublicScanTeaserResponse(
    val status: String,
    val domain: String,
    val verdict: PublicScanVerdict?,
) {
    companion object {
        fun from(
            scan: PublicScanEntity,
            cookies: List<PublicScanCookieEntity>,
            now: Instant,
        ): PublicScanTeaserResponse =
            PublicScanTeaserResponse(
                status = scan.status.dbValue,
                domain = scan.domain,
                verdict =
                    if (scan.status == ScanStatus.DONE) {
                        PublicScanVerdict.from(cookies, now, scan.marketingTrackerCount ?: 0)
                    } else {
                        null
                    },
            )
    }
}

/**
 * The free headline result: counts plus the indicative [compliance] score/issue list.
 * [cookiesByCategory] maps each canonical [com.complyr.banner.ConsentCategory] key to how many
 * classified cookies fell in it (the site localizes the key), and [needsReviewCount] is how many the
 * signature DB did not recognize. Counts, never names — the per-cookie detail stays the gated upsell.
 *
 * [compliance] is the same [ComplianceReport] the authenticated per-site scan surfaces: a 0–100 score
 * and severity-ranked issue *codes* with counts, but no cookie names — so it motivates the fix
 * ("here is what's wrong and how bad") without giving away the gated detail.
 */
data class PublicScanVerdict(
    val totalCookies: Int,
    val cookiesByCategory: Map<String, Int>,
    val needsReviewCount: Int,
    val compliance: ComplianceReport,
) {
    companion object {
        fun from(
            cookies: List<PublicScanCookieEntity>,
            now: Instant,
            marketingTrackerCount: Int,
        ): PublicScanVerdict {
            val (classified, unrecognized) = cookies.partition { it.isKnown && it.category != null }
            return PublicScanVerdict(
                totalCookies = cookies.size,
                cookiesByCategory = classified.groupingBy { requireNotNull(it.category) }.eachCount(),
                needsReviewCount = unrecognized.size,
                compliance = ComplianceAnalyzer.analyze(cookies, now, marketingTrackerCount),
            )
        }
    }
}

/** One cookie in the unlocked report — the same shape the owned-scan UI renders ([ScanCookieResponse]). */
data class PublicScanCookieResponse(
    val name: String,
    val domain: String?,
    val expiry: String?,
    val category: String?,
    val provider: String?,
    val isKnown: Boolean,
) {
    companion object {
        fun from(cookie: PublicScanCookieEntity): PublicScanCookieResponse =
            PublicScanCookieResponse(
                name = cookie.name,
                domain = cookie.domain,
                expiry = cookie.expiry,
                category = cookie.category,
                provider = cookie.provider,
                isKnown = cookie.isKnown,
            )
    }
}

/**
 * The email-gated detailed report: the teaser [verdict] plus the full per-cookie breakdown, split the
 * same way as the owned-scan [ScanDetailResponse] — [cookiesByCategory] for classified cookies keyed
 * by canonical category, [needsReview] for the ones the signature DB did not recognize.
 */
data class PublicScanReportResponse(
    val status: String,
    val domain: String,
    val verdict: PublicScanVerdict,
    val cookiesByCategory: Map<String, List<PublicScanCookieResponse>>,
    val needsReview: List<PublicScanCookieResponse>,
) {
    companion object {
        fun from(
            scan: PublicScanEntity,
            cookies: List<PublicScanCookieEntity>,
            now: Instant,
        ): PublicScanReportResponse {
            // Same classifier invariant as ScanDetailResponse: isKnown ⇒ category set; anything else is
            // a needs-review item so a cookie can never silently vanish from the report.
            val (classified, unrecognized) = cookies.partition { it.isKnown && it.category != null }
            return PublicScanReportResponse(
                status = scan.status.dbValue,
                domain = scan.domain,
                verdict = PublicScanVerdict.from(cookies, now, scan.marketingTrackerCount ?: 0),
                cookiesByCategory =
                    classified.groupBy(
                        { requireNotNull(it.category) },
                        { PublicScanCookieResponse.from(it) },
                    ),
                needsReview = unrecognized.map(PublicScanCookieResponse::from),
            )
        }
    }
}
