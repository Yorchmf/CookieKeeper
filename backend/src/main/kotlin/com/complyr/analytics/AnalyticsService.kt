package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.analytics.dto.CategoryCount
import com.complyr.analytics.dto.ConsentAnalytics
import com.complyr.analytics.dto.ConsentTrendPoint
import com.complyr.analytics.dto.CookieAnalytics
import com.complyr.analytics.dto.PolicyAnalytics
import com.complyr.analytics.dto.SiteAnalyticsResponse
import com.complyr.banner.ConsentCategory
import com.complyr.policy.PolicyRepository
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanCookieView
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read side of the customer-facing site analytics. Every read is ownership-gated first (`findByIdAndUserId`)
 * so another user's site id is indistinguishable from a true miss (matching [com.complyr.consent.ConsentLogService]).
 * The window comes from [AnalyticsRangeResolver] — never derived here, so the plan retention floor (ADR-16)
 * cannot be bypassed — then the consent aggregates, latest-scan cookie inventory, and current policy version
 * are assembled into one response.
 *
 * Purely a dashboard read: it never touches the consent-ingestion path (CLAUDE.md #3) and never mutates.
 */
@Service
class AnalyticsService(
    private val siteRepository: SiteRepository,
    private val consentAnalyticsRepository: ConsentAnalyticsRepository,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val policyRepository: PolicyRepository,
    private val rangeResolver: AnalyticsRangeResolver,
    private val consentAnalyticsAssembler: ConsentAnalyticsAssembler,
) {
    @Transactional(readOnly = true)
    fun summarize(
        userId: UUID,
        siteId: UUID,
        filter: AnalyticsFilter,
    ): SiteAnalyticsResponse {
        requireOwnedSite(userId, siteId)
        val range = rangeResolver.resolve(userId, filter)
        return SiteAnalyticsResponse(
            range = range,
            consent = consentAnalytics(siteId, range),
            cookies = cookieAnalytics(siteId),
            policy = policyAnalytics(siteId),
        )
    }

    /** The consent-trend series alone (the CSV export payload); ownership already asserted by the caller. */
    @Transactional(readOnly = true)
    fun consentTrend(
        userId: UUID,
        siteId: UUID,
        filter: AnalyticsFilter,
    ): List<ConsentTrendPoint> {
        requireOwnedSite(userId, siteId)
        val range = rangeResolver.resolve(userId, filter)
        return consentAnalyticsAssembler.trend(consentAnalyticsRepository.dailyActionCounts(siteId, range.from, range.to))
    }

    private fun consentAnalytics(
        siteId: UUID,
        range: AnalyticsRange,
    ): ConsentAnalytics =
        consentAnalyticsAssembler.assemble(
            daily = consentAnalyticsRepository.dailyActionCounts(siteId, range.from, range.to),
            optIn = consentAnalyticsRepository.categoryOptInCounts(siteId, range.from, range.to),
            languages = consentAnalyticsRepository.languageCounts(siteId, range.from, range.to),
        )

    private fun cookieAnalytics(siteId: UUID): CookieAnalytics? {
        val scan = scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) ?: return null
        val cookies = scanCookieRepository.findByScanId(scan.id)
        val (classified, unclassified) = cookies.partition { it.isKnown && it.category != null }
        val nonNecessary = classified.filter { it.category != ConsentCategory.NECESSARY.key }

        // byCategory: the four taxonomy buckets in canonical order, then an `unclassified` bucket. Buckets with
        // zero cookies are dropped so the chart shows only categories actually observed.
        val byCategory =
            buildList {
                for (category in ConsentCategory.entries) {
                    val count = classified.count { it.category == category.key }
                    if (count > 0) add(CategoryCount(category.key, count))
                }
                if (unclassified.isNotEmpty()) add(CategoryCount(CATEGORY_UNCLASSIFIED, unclassified.size))
            }

        return CookieAnalytics(
            scanId = scan.id,
            scannedAt = scan.finishedAt ?: scan.createdAt,
            total = cookies.size,
            byCategory = byCategory,
            known = classified.size,
            unknown = unclassified.size,
            insecure = nonNecessary.count(::isInsecure),
            trackerCount = scan.marketingTrackerCount ?: 0,
        )
    }

    // Mirrors ComplianceAnalyzer: a non-essential cookie carrying neither Secure nor HttpOnly is sent in the
    // clear and readable from page script.
    private fun isInsecure(cookie: ScanCookieView): Boolean = !cookie.secure && !cookie.httpOnly

    private fun policyAnalytics(siteId: UUID): PolicyAnalytics? {
        val latest = policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) ?: return null
        val languages =
            policyRepository
                .findBySiteIdAndVersion(siteId, latest.version)
                .map { it.language }
                .distinct()
                .sorted()
        return PolicyAnalytics(version = latest.version, publishedAt = latest.publishedAt, languages = languages)
    }

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ) {
        siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
    }

    companion object {
        const val ACTION_ACCEPT_ALL = "accept_all"
        const val ACTION_REJECT_ALL = "reject_all"
        const val ACTION_CUSTOM = "custom"
        const val CATEGORY_UNCLASSIFIED = "unclassified"
    }
}
