package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.billing.EntitlementService
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
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
    ): AnalyticsRange {
        val to = filter.to ?: clock.instant()
        val requestedFrom = filter.from ?: to.minus(DEFAULT_WINDOW)
        val floor = entitlementService.consentRetentionFloor(userId)
        return AnalyticsRange(from = maxOf(requestedFrom, floor), to = to)
    }

    companion object {
        /** Default window when the caller supplies no `from`: the trailing 30 days. */
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
    }
}
