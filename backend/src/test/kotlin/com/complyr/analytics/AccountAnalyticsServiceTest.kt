package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AccountAnalyticsService] — the cross-site consent roll-up. Repositories are mocked: what is
 * under test is the account-scoping (ACTIVE sites only, empty guard) and that the resolved window reaches the
 * queries. The aggregation arithmetic is [ConsentAnalyticsAssembler]'s (its own test), exercised here with a
 * real assembler so the wiring is proven end to end.
 */
class AccountAnalyticsServiceTest {
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-14T12:00:00Z")
    private val range = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)

    private val siteRepository = mockk<SiteRepository>()
    private val consentRepository = mockk<ConsentAnalyticsRepository>()
    private val impressionRepository = mockk<BannerImpressionRepository>()
    private val floor = now.minusSeconds(365 * 86_400)
    private val prior = AnalyticsRange(from = range.from.minusSeconds(30 * 86_400), to = range.from)
    private val rangeResolver =
        mockk<AnalyticsRangeResolver> {
            // The floor is resolved once and threaded into both resolve and priorWindow (one plan lookup per read).
            every { retentionFloor(userId) } returns floor
            every { resolve(any(), floor) } returns range
            // No comparable prior window by default; the delta test overrides this.
            every { priorWindow(range, floor) } returns null
        }
    private val service =
        AccountAnalyticsService(
            siteRepository,
            consentRepository,
            impressionRepository,
            ConsentAnalyticsAssembler(),
            rangeResolver,
        )

    private fun site() = SiteEntity(userId = userId, domain = "d-${UUID.randomUUID()}.eu", siteKey = "k")

    @Test
    fun `account with no active sites returns an empty roll-up without touching the IN-clause queries`() {
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns emptyList()

        val result = service.rollup(userId, AnalyticsFilter())

        assertEquals(range, result.range)
        assertEquals(0, result.siteCount)
        assertEquals(0, result.consent.totalEvents)
        assertNull(result.previous)
        assertTrue(result.consent.trend.isEmpty())
        // `site_id IN (...)` is invalid SQL for an empty set: the service must return before reaching it.
        verify(exactly = 0) { consentRepository.accountDailyActionCounts(any(), any(), any()) }
        verify(exactly = 0) { consentRepository.accountCategoryOptInCounts(any(), any(), any()) }
        verify(exactly = 0) { consentRepository.accountLanguageCounts(any(), any(), any()) }
        verify(exactly = 0) { impressionRepository.accountImpressionCounts(any(), any(), any()) }
    }

    @Test
    fun `rolls up consent across every active site and reports the site count`() {
        val siteA = site()
        val siteB = site()
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns listOf(siteA, siteB)
        val siteIds = listOf(siteA.id, siteB.id)
        val day = LocalDate.parse("2026-08-13")
        every { consentRepository.accountDailyActionCounts(siteIds, range.from, range.to) } returns
            listOf(
                DailyActionCount(day, "accept_all", 5),
                DailyActionCount(day, "reject_all", 3),
                DailyActionCount(day, "custom", 2),
            )
        every { consentRepository.accountCategoryOptInCounts(siteIds, range.from, range.to) } returns
            listOf(CategoryOptInCount(category = "statistics", optIns = 6, decisions = 10))
        every { consentRepository.accountLanguageCounts(siteIds, range.from, range.to) } returns
            listOf(LanguageCountRow("en", 7), LanguageCountRow("de", 3))
        // 10 decisions across the portfolio against 40 impressions → 0.25 interaction rate.
        every { impressionRepository.accountImpressionCounts(siteIds, range.from, range.to) } returns 40L

        val result = service.rollup(userId, AnalyticsFilter())

        assertEquals(2, result.siteCount)
        assertEquals(10, result.consent.totalEvents)
        assertEquals(40, result.consent.impressions)
        assertEquals(0.25, result.consent.interactionRate)
        assertEquals(5, result.consent.byAction.acceptAll)
        assertEquals(
            0.6,
            result.consent.categoryOptIn
                .single()
                .rate,
        )
        assertEquals(1, result.consent.trend.size)
        assertEquals(
            10,
            result.consent.trend
                .single()
                .total,
        )
        assertEquals(listOf("en", "de"), result.consent.languageSplit.map { it.lang })
        // No comparable prior window (default stub) → no baseline on the response.
        assertNull(result.previous)
    }

    @Test
    fun `attaches a prior-window consent baseline aggregated over the same sites when one is comparable`() {
        val siteA = site()
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns listOf(siteA)
        val siteIds = listOf(siteA.id)
        val day = LocalDate.parse("2026-08-13")
        every { consentRepository.accountDailyActionCounts(siteIds, range.from, range.to) } returns
            listOf(DailyActionCount(day, "accept_all", 8), DailyActionCount(day, "reject_all", 2))
        every { consentRepository.accountCategoryOptInCounts(siteIds, range.from, range.to) } returns emptyList()
        every { consentRepository.accountLanguageCounts(siteIds, range.from, range.to) } returns emptyList()
        every { impressionRepository.accountImpressionCounts(siteIds, range.from, range.to) } returns 0L
        // A comparable prior window exists; its consent is aggregated over the *same* current portfolio.
        every { rangeResolver.priorWindow(range, floor) } returns prior
        val priorDay = LocalDate.parse("2026-07-14")
        every { consentRepository.accountDailyActionCounts(siteIds, prior.from, prior.to) } returns
            listOf(DailyActionCount(priorDay, "accept_all", 3), DailyActionCount(priorDay, "custom", 1))
        every { impressionRepository.accountImpressionCounts(siteIds, prior.from, prior.to) } returns 20L

        val result = service.rollup(userId, AnalyticsFilter())

        val previous = requireNotNull(result.previous)
        assertEquals(4, previous.totalEvents)
        assertEquals(3, previous.byAction.acceptAll)
        assertEquals(1, previous.byAction.custom)
        // The prior window's impressions ride the same baseline, aggregated over the same sites.
        assertEquals(20, previous.impressions)
        // The baseline reads the shifted prior window, not the current one.
        verify { consentRepository.accountDailyActionCounts(siteIds, prior.from, prior.to) }
        verify { impressionRepository.accountImpressionCounts(siteIds, prior.from, prior.to) }
    }

    @Test
    fun `resolves the window through the range resolver and passes it to the queries`() {
        val siteA = site()
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns listOf(siteA)
        val siteIds = listOf(siteA.id)
        every { consentRepository.accountDailyActionCounts(siteIds, range.from, range.to) } returns emptyList()
        every { consentRepository.accountCategoryOptInCounts(siteIds, range.from, range.to) } returns emptyList()
        every { consentRepository.accountLanguageCounts(siteIds, range.from, range.to) } returns emptyList()
        every { impressionRepository.accountImpressionCounts(siteIds, range.from, range.to) } returns 0L

        val result = service.rollup(userId, AnalyticsFilter())

        assertEquals(range, result.range)
        // The floored window (ADR-16) reaches the aggregate queries, not a range re-derived here.
        verify { consentRepository.accountDailyActionCounts(siteIds, range.from, range.to) }
    }
}
