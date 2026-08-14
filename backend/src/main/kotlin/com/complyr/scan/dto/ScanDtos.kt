package com.complyr.scan.dto

import com.complyr.billing.RescanFrequency
import com.complyr.scan.ComplianceAnalyzer
import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanDiff
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

/**
 * A scan as it appears in a site's scan history list — status/counts only, no cookie payload.
 *
 * [newCookieCount] is how many cookie names this scan found that the previous completed scan on the same
 * page did not — the "+N new" badge the history list shows. It is null when there is nothing to compare
 * against on the page (a non-`done` scan, or the oldest `done` scan whose predecessor is off the page):
 * [ScanQueryService.list] computes it from a single batch read rather than an N+1, so the badge is a cheap
 * in-page hint and the scan detail view is the authoritative diff.
 */
data class ScanSummaryResponse(
    val id: UUID,
    val status: String,
    val trigger: String,
    val pagesCrawled: Int?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val error: String?,
    val createdAt: Instant,
    val newCookieCount: Int? = null,
) {
    companion object {
        fun from(
            scan: ScanEntity,
            newCookieCount: Int? = null,
        ): ScanSummaryResponse =
            ScanSummaryResponse(
                id = scan.id,
                status = scan.status.dbValue,
                trigger = scan.trigger.dbValue,
                pagesCrawled = scan.pagesCrawled,
                startedAt = scan.startedAt,
                finishedAt = scan.finishedAt,
                error = scan.error,
                createdAt = scan.createdAt,
                newCookieCount = newCookieCount,
            )
    }
}

/**
 * How a completed scan's findings differ from the previous completed scan of the same site (see [ScanDiff]).
 * [hasPrevious] is false for the site's first completed scan, in which case the lists are empty and the UI
 * shows no comparison. Cookie names are compared, never row identity; trackers are a count-only delta
 * because raw hosts are never stored.
 */
data class ScanDiffResponse(
    val hasPrevious: Boolean,
    val previousScanId: UUID?,
    val previousScanAt: Instant?,
    val newCookieCount: Int,
    val removedCookieCount: Int,
    val addedCookieNames: List<String>,
    val removedCookieNames: List<String>,
    val trackerCountDelta: Int?,
) {
    companion object {
        fun from(diff: ScanDiff): ScanDiffResponse =
            ScanDiffResponse(
                hasPrevious = diff.hasPrevious,
                previousScanId = diff.previousScanId,
                previousScanAt = diff.previousScanAt,
                newCookieCount = diff.newCookieCount,
                removedCookieCount = diff.removedCookieCount,
                addedCookieNames = diff.addedCookieNames,
                removedCookieNames = diff.removedCookieNames,
                trackerCountDelta = diff.trackerCountDelta,
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
 *
 * [diff] is how this scan's findings changed since the previous completed scan of the same site; like
 * [compliance] it is only present for a `done` scan, and is `null` otherwise (an in-flight/failed scan has
 * nothing to compare).
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
    val diff: ScanDiffResponse?,
) {
    companion object {
        fun from(
            scan: ScanEntity,
            cookies: List<ScanCookieEntity>,
            now: Instant,
            diff: ScanDiff? = null,
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
                // A null persisted count (historical/in-flight row) scores as 0 trackers.
                compliance =
                    if (scan.status == ScanStatus.DONE) {
                        ComplianceAnalyzer.analyze(cookies, now, scan.marketingTrackerCount ?: 0)
                    } else {
                        null
                    },
                // The caller computes the diff only for a `done` scan (nothing to compare otherwise) and
                // passes null for the rest; mirror the compliance gate so a stray diff can't leak through.
                diff = if (scan.status == ScanStatus.DONE) diff?.let(ScanDiffResponse::from) else null,
            )
        }
    }
}

/** Why the nightly re-scan job would never come back to a site — the `reason` on an unscheduled answer. */
enum class UnscheduledReason {
    /** The site is archived, so it is filtered out of the candidate query entirely. */
    ARCHIVED,

    /** The trial elapsed with no subscription: the job skips the account ("no new sites, no scans"). */
    LAPSED,

    /** On trial, but the next cycle falls due after the trial ends — see [ScanScheduleResponse]. */
    TRIAL_ENDS_FIRST,
    ;

    /** Lowercased wire token, matching how `frequency` is rendered. */
    fun token(): String = name.lowercase()
}

/**
 * When the nightly re-scan job will next come back to a site (`GET /api/v1/sites/{siteId}/scan-schedule`),
 * so the scan-history card can answer "why does this look stale?" with a date instead of only a cadence.
 *
 * [scheduled] is false when the job would never pick the site up at all, and [reason] then says which of
 * [UnscheduledReason] applies so the dashboard can explain it rather than showing one vague line. The
 * `TRIAL_ENDS_FIRST` case is the subtle one: a trial carries Starter's monthly cadence but runs for two
 * weeks, so a site scanned today comes due after the account has already lapsed to `Expired`.
 *
 * When [scheduled] is true, [frequency] is the plan cadence (lowercased `weekly` / `monthly`, the same
 * token `EntitlementLimits.rescanFrequency` uses, so the dashboard maps it with one message set) and
 * [nextScanAt] is the exact instant the job treats the site as due — null only for a never-scanned site,
 * which is due immediately rather than on a date. A [nextScanAt] in the past means the site is already due
 * and will be picked up by an upcoming nightly run (the job caps how many sites it enqueues per night, so
 * a large backlog can take more than one).
 *
 * Build it through [scheduled] / [unscheduled] rather than the constructor: those are what keep "scheduled
 * with no cadence" and "unscheduled but here's a date anyway" from being representable.
 */
data class ScanScheduleResponse(
    val scheduled: Boolean,
    val frequency: String? = null,
    val nextScanAt: Instant? = null,
    val reason: String? = null,
) {
    companion object {
        fun scheduled(
            frequency: RescanFrequency,
            nextScanAt: Instant?,
        ) = ScanScheduleResponse(
            scheduled = true,
            frequency = frequency.name.lowercase(),
            nextScanAt = nextScanAt,
        )

        fun unscheduled(reason: UnscheduledReason) = ScanScheduleResponse(scheduled = false, reason = reason.token())
    }
}
