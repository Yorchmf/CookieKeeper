package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.billing.EntitlementService
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Resolves the window a consent-evidence read may cover: the caller's filter, defaulted to the trailing
 * [DEFAULT_WINDOW], then FLOORED at the account's plan retention.
 *
 * Extracted so there is exactly one place that applies [EntitlementService.consentRetentionFloor] to a
 * read window. Both the per-site analytics ([AnalyticsService]) and the account-level dashboard home
 * ([OverviewService]) go through it, so neither can be given a new query that quietly reads past the
 * retention the customer's plan sells them (ADR-16's read-layer half). Any future consent-evidence read
 * should resolve its window here rather than re-deriving one.
 */
@Component
class AnalyticsRangeResolver(
    private val entitlementService: EntitlementService,
    private val clock: Clock,
) {
    /**
     * The effective window for [userId]. A Starter account asking for 90 days silently gets only what its
     * 12-month retention allows — a floor, never an error: asking for older data yields fewer rows, not a
     * rejection.
     */
    fun resolve(
        userId: UUID,
        filter: AnalyticsFilter,
    ): AnalyticsRange = resolve(filter, retentionFloor(userId))

    /**
     * [resolve] against an already-resolved retention [floor]. A read that also needs [priorWindow] resolves the
     * floor once (via [retentionFloor]) and threads the same instant into both, so the current window and its
     * baseline are clamped against one floor — two independent resolutions can't disagree if the plan changes
     * mid-request, and the read costs a single subscription lookup rather than two.
     */
    fun resolve(
        filter: AnalyticsFilter,
        floor: Instant,
    ): AnalyticsRange {
        val to = filter.to ?: clock.instant()
        val requestedFrom = filter.from ?: to.minus(DEFAULT_WINDOW)
        return AnalyticsRange(from = maxOf(requestedFrom, floor), to = to)
    }

    /** The plan retention floor for [userId] (ADR-16): the oldest instant a consent-evidence read may reach. */
    fun retentionFloor(userId: UUID): Instant = entitlementService.consentRetentionFloor(userId)

    /**
     * The window immediately preceding [current], shifted back by [current]'s own length — the baseline for a
     * period-over-period delta. Returns null when the whole prior window would reach below the plan retention
     * [floor] (ADR-16): a baseline that is retention-clipped (and so shorter than the current window) would make
     * the delta misleading, so we omit the comparison rather than draw it against a partial window. The caller
     * passes the same [floor] used to clamp [current], so a delta can never quietly read past what the customer's
     * plan sells them either.
     */
    fun priorWindow(
        current: AnalyticsRange,
        floor: Instant,
    ): AnalyticsRange? {
        val priorFrom = current.from.minus(Duration.between(current.from, current.to))
        if (priorFrom.isBefore(floor)) return null
        return AnalyticsRange(from = priorFrom, to = current.from)
    }

    companion object {
        /** Default window when the caller supplies no `from`: the trailing 30 days. */
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
    }
}
