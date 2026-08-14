package com.complyr.analytics

import com.complyr.analytics.dto.AccountAnalyticsResponse
import com.complyr.analytics.dto.ActionBreakdown
import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.ConsentAnalytics
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val consentAnalyticsAssembler: ConsentAnalyticsAssembler,
    private val rangeResolver: AnalyticsRangeResolver,
) {
    @Transactional(readOnly = true)
    fun rollup(
        userId: UUID,
        filter: AnalyticsFilter,
    ): AccountAnalyticsResponse {
        val range = rangeResolver.resolve(userId, filter)
        val siteIds = siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE).map { it.id }

        // Early return before any aggregate query: `site_id IN (...)` is invalid SQL for an empty set, and an
        // account with no active sites is a real state (brand-new, or everything archived).
        if (siteIds.isEmpty()) return AccountAnalyticsResponse(range, EMPTY_CONSENT, siteCount = 0)

        val consent =
            consentAnalyticsAssembler.assemble(
                daily = consentAnalyticsRepository.accountDailyActionCounts(siteIds, range.from, range.to),
                optIn = consentAnalyticsRepository.accountCategoryOptInCounts(siteIds, range.from, range.to),
                languages = consentAnalyticsRepository.accountLanguageCounts(siteIds, range.from, range.to),
            )
        return AccountAnalyticsResponse(range, consent, siteCount = siteIds.size)
    }

    private companion object {
        val EMPTY_CONSENT =
            ConsentAnalytics(
                totalEvents = 0,
                byAction = ActionBreakdown(acceptAll = 0, rejectAll = 0, custom = 0),
                trend = emptyList(),
                categoryOptIn = emptyList(),
                languageSplit = emptyList(),
            )
    }
}
