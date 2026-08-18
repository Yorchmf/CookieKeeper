package com.complyr.analytics

import com.complyr.analytics.dto.AccountAnalyticsResponse
import com.complyr.analytics.dto.ActionBreakdown
import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.analytics.dto.ConsentAnalytics
import com.complyr.analytics.dto.PeriodSummary
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Read side of the cross-site (account-level) consent analytics — the "All Sites" roll-up for multi-site
 * customers. The account-level companion to [AnalyticsService]: same window resolution and the same
 * [ConsentAnalyticsAssembler] arithmetic, but aggregated over every ACTIVE site the account owns rather than
 * one named site.
 *
 * Ownership is scoped by construction, exactly like [OverviewService]: the site set is fetched by `userId`
 * and every consent query is keyed on those ids, so there is no site id to smuggle in and nothing to gate
 * beyond the account itself. Archived sites are excluded so the roll-up reflects the current portfolio. The
 * window comes from [AnalyticsRangeResolver], so the plan retention floor (ADR-16) cannot be bypassed here.
 * Purely a dashboard read: never touches the consent-ingestion path (CLAUDE.md #3) and never mutates.
 */
@Service
class AccountAnalyticsService(
    private val siteRepository: SiteRepository,
    private val consentAnalyticsRepository: ConsentAnalyticsRepository,
    private val bannerImpressionRepository: BannerImpressionRepository,
    private val consentAnalyticsAssembler: ConsentAnalyticsAssembler,
    private val rangeResolver: AnalyticsRangeResolver,
) {
    @Transactional(readOnly = true)
    fun rollup(
        userId: UUID,
        filter: AnalyticsFilter,
    ): AccountAnalyticsResponse {
        val floor = rangeResolver.retentionFloor(userId)
        val range = rangeResolver.resolve(filter, floor)
        val siteIds = siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE).map { it.id }

        // Early return before any aggregate query: `site_id IN (...)` is invalid SQL for an empty set, and an
        // account with no active sites is a real state (brand-new, or everything archived). No prior window to
        // compare either — a portfolio with nothing in it has no baseline.
        if (siteIds.isEmpty()) return AccountAnalyticsResponse(range, EMPTY_CONSENT, previous = null, siteCount = 0)

        val consent =
            consentAnalyticsAssembler.assemble(
                daily = consentAnalyticsRepository.accountDailyActionCounts(siteIds, range.from, range.to),
                optIn = consentAnalyticsRepository.accountCategoryOptInCounts(siteIds, range.from, range.to),
                languages = consentAnalyticsRepository.accountLanguageCounts(siteIds, range.from, range.to),
                impressions = bannerImpressionRepository.accountImpressionCounts(siteIds, range.from, range.to),
            )
        return AccountAnalyticsResponse(range, consent, previous = previousSummary(siteIds, range, floor), siteCount = siteIds.size)
    }

    /**
     * The consent baseline for the window before [range] (same length), aggregated over the same [siteIds], for
     * period-over-period deltas — or null when [AnalyticsRangeResolver.priorWindow] finds no comparable window
     * (it would read past the plan retention [floor], the same one that clamped [range]). The prior window is
     * compared against the account's *current* portfolio, the same set the displayed figures cover.
     */
    private fun previousSummary(
        siteIds: List<UUID>,
        range: AnalyticsRange,
        floor: Instant,
    ): PeriodSummary? =
        rangeResolver.priorWindow(range, floor)?.let { prior ->
            consentAnalyticsAssembler.summarize(
                consentAnalyticsRepository.accountDailyActionCounts(siteIds, prior.from, prior.to),
                bannerImpressionRepository.accountImpressionCounts(siteIds, prior.from, prior.to),
            )
        }

    private companion object {
        val EMPTY_CONSENT =
            ConsentAnalytics(
                totalEvents = 0,
                byAction = ActionBreakdown(acceptAll = 0, rejectAll = 0, custom = 0),
                impressions = 0,
                interactionRate = 0.0,
                trend = emptyList(),
                categoryOptIn = emptyList(),
                languageSplit = emptyList(),
            )
    }
}
