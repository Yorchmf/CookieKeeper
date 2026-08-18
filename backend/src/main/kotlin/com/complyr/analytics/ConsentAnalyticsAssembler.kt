package com.complyr.analytics

import com.complyr.analytics.dto.ActionBreakdown
import com.complyr.analytics.dto.CategoryOptIn
import com.complyr.analytics.dto.ConsentAnalytics
import com.complyr.analytics.dto.ConsentTrendPoint
import com.complyr.analytics.dto.LanguageCount
import com.complyr.analytics.dto.PeriodSummary
import org.springframework.stereotype.Component

/**
 * Builds the [ConsentAnalytics] view from the three raw aggregate row sets a consent read produces: the daily
 * per-action counts, the per-category opt-in tallies, and the language tallies.
 *
 * Extracted so the per-site read ([AnalyticsService]) and the cross-site roll-up ([AccountAnalyticsService])
 * share ONE definition of the daily trend series, the accept/reject/custom breakdown, and the opt-in rate
 * math. In a compliance product these figures must be identical whether a customer looks at one site or all
 * of them; a divergence between two copies of this arithmetic would be a reporting bug, so there is only one
 * copy. Pure and stateless — the SQL lives in [ConsentAnalyticsRepository], the windowing in
 * [AnalyticsRangeResolver].
 */
@Component
class ConsentAnalyticsAssembler {
    fun assemble(
        daily: List<DailyActionCount>,
        optIn: List<CategoryOptInCount>,
        languages: List<LanguageCountRow>,
        impressions: Long,
    ): ConsentAnalytics {
        val summary = summarize(daily, impressions)
        return ConsentAnalytics(
            totalEvents = summary.totalEvents,
            byAction = summary.byAction,
            impressions = summary.impressions,
            // 0.0 (not a division-by-zero) when the banner recorded no impressions over the window.
            interactionRate = interactionRate(summary.totalEvents, impressions),
            trend = trend(daily),
            categoryOptIn =
                optIn.map {
                    CategoryOptIn(
                        category = it.category,
                        optIns = it.optIns,
                        decisions = it.decisions,
                        // 0.0 (not a division-by-zero) when the category was carried by no decision.
                        rate = if (it.decisions == 0L) 0.0 else it.optIns.toDouble() / it.decisions,
                    )
                },
            languageSplit = languages.map { LanguageCount(lang = it.lang, count = it.count) },
        )
    }

    /**
     * The accept/reject/custom totals for a set of daily rows, and their sum — the lean baseline a
     * period-over-period delta needs, and the same arithmetic [assemble] uses for its own breakdown, so the
     * current window and its prior-window comparison can never disagree on how an action mix is counted.
     */
    fun summarize(
        daily: List<DailyActionCount>,
        impressions: Long,
    ): PeriodSummary {
        val byAction =
            ActionBreakdown(
                acceptAll = daily.filter { it.action == AnalyticsService.ACTION_ACCEPT_ALL }.sumOf { it.count },
                rejectAll = daily.filter { it.action == AnalyticsService.ACTION_REJECT_ALL }.sumOf { it.count },
                custom = daily.filter { it.action == AnalyticsService.ACTION_CUSTOM }.sumOf { it.count },
            )
        return PeriodSummary(
            totalEvents = byAction.acceptAll + byAction.rejectAll + byAction.custom,
            byAction = byAction,
            impressions = impressions,
        )
    }

    /**
     * Fraction of banner impressions that produced a consent decision — the one definition of interaction
     * rate, shared by the per-site read and the cross-site roll-up so the two can never disagree. 0.0 (not a
     * division-by-zero) when there were no impressions; see [ConsentAnalytics.interactionRate] for why the
     * ratio can legitimately exceed 1.0 at the window edges.
     */
    fun interactionRate(
        totalEvents: Long,
        impressions: Long,
    ): Double = if (impressions == 0L) 0.0 else totalEvents.toDouble() / impressions

    /**
     * Collapse the (day, action) rows into one dense point per day, ascending — the series the client charts,
     * and the CSV export payload ([AnalyticsService.consentTrend]).
     */
    fun trend(daily: List<DailyActionCount>): List<ConsentTrendPoint> =
        daily
            .groupBy { it.day }
            .toSortedMap()
            .map { (day, rows) ->
                val accept = rows.filter { it.action == AnalyticsService.ACTION_ACCEPT_ALL }.sumOf { it.count }
                val reject = rows.filter { it.action == AnalyticsService.ACTION_REJECT_ALL }.sumOf { it.count }
                val custom = rows.filter { it.action == AnalyticsService.ACTION_CUSTOM }.sumOf { it.count }
                ConsentTrendPoint(
                    date = day,
                    acceptAll = accept,
                    rejectAll = reject,
                    custom = custom,
                    total = accept + reject + custom,
                )
            }
}
