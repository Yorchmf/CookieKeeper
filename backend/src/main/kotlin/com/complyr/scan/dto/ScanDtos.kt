package com.complyr.scan.dto

import com.complyr.scan.ComplianceAnalyzer
import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanEntity
import com.complyr.scan.ScanStatus
import java.time.Instant
import java.util.UUID

/** One cookie observed in a scan, as the dashboard renders it. Category/provider are null until classified. */
data class ScanCookieResponse(
    val name: String,
    val domain: String?,
    val expiry: String?,
    val category: String?,
    val provider: String?,
    val isKnown: Boolean,
) {
    companion object {
        fun from(cookie: ScanCookieEntity): ScanCookieResponse =
            ScanCookieResponse(
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
 * Acknowledgement of an accepted re-scan request: the scan row exists and is `queued`, but the crawl has
 * not started. The dashboard invalidates its scan list on this and lets the existing 3s poll show the
 * scan progress, so nothing beyond the id is needed here.
 */
data class ScanRequestedResponse(
    val scanId: UUID,
    val status: String = ScanStatus.QUEUED.dbValue,
)

/** A scan as it appears in a site's scan history list — status/counts only, no cookie payload. */
data class ScanSummaryResponse(
    val id: UUID,
    val status: String,
    val trigger: String,
    val pagesCrawled: Int?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val error: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(scan: ScanEntity): ScanSummaryResponse =
            ScanSummaryResponse(
                id = scan.id,
                status = scan.status.dbValue,
                trigger = scan.trigger.dbValue,
                pagesCrawled = scan.pagesCrawled,
                startedAt = scan.startedAt,
                finishedAt = scan.finishedAt,
                error = scan.error,
                createdAt = scan.createdAt,
            )
    }
}

/**
 * A scan plus its cookies, split for the results UI: [cookiesByCategory] holds classified cookies keyed
 * by their canonical [com.complyr.banner.ConsentCategory] key (the dashboard localizes the key), and
 * [needsReview] holds cookies the signature DB did not recognize (isKnown = false) for the customer to
 * categorize. Category keys are stable machine tokens, never user-facing text (i18n from day one).
 *
 * [compliance] is the derived score + issue list ([ComplianceAnalyzer]); it is populated only for a
 * `done` scan (a queued/running/failed scan has no meaningful findings yet) and is `null` otherwise.
 */
data class ScanDetailResponse(
    val id: UUID,
    val status: String,
    val trigger: String,
    val pagesCrawled: Int?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val error: String?,
    val createdAt: Instant,
    val cookiesByCategory: Map<String, List<ScanCookieResponse>>,
    val needsReview: List<ScanCookieResponse>,
    val compliance: ComplianceReport?,
) {
    companion object {
        fun from(
            scan: ScanEntity,
            cookies: List<ScanCookieEntity>,
            now: Instant,
        ): ScanDetailResponse {
            // A classified cookie always carries a category (classifier invariant: isKnown ⇒ category
            // set); anything else — unknown, or the defensive known-but-uncategorized case — is a
            // needs-review item so it can never silently vanish from the customer's view.
            val (classified, unrecognized) = cookies.partition { it.isKnown && it.category != null }
            return ScanDetailResponse(
                id = scan.id,
                status = scan.status.dbValue,
                trigger = scan.trigger.dbValue,
                pagesCrawled = scan.pagesCrawled,
                startedAt = scan.startedAt,
                finishedAt = scan.finishedAt,
                error = scan.error,
                createdAt = scan.createdAt,
                cookiesByCategory =
                    classified.groupBy(
                        { requireNotNull(it.category) },
                        { ScanCookieResponse.from(it) },
                    ),
                needsReview = unrecognized.map(ScanCookieResponse::from),
                // Only a completed crawl has meaningful findings; an in-flight/failed scan carries no score.
                compliance = if (scan.status == ScanStatus.DONE) ComplianceAnalyzer.analyze(cookies, now) else null,
            )
        }
    }
}
