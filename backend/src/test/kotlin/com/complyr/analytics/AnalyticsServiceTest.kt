package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.policy.PolicyRepository
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [AnalyticsService], focused on the period-over-period baseline wiring (`previous`). The
 * arithmetic lives in [ConsentAnalyticsAssembler] and the window math in [AnalyticsRangeResolver] (their own
 * tests); what is proven here is that `summarize` threads the resolved window and its floor into the prior
 * read, populates `previous` from the *shifted* window, and omits it when there is no comparable prior window.
 * Cookie and policy sources are stubbed empty so the test exercises only the consent path.
 */
class AnalyticsServiceTest {
    private val userId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-14T12:00:00Z")
    private val floor = now.minusSeconds(365 * 86_400)
    private val range = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)
    private val prior = AnalyticsRange(from = range.from.minusSeconds(30 * 86_400), to = range.from)

    private val siteRepository = mockk<SiteRepository>()
    private val consentRepository = mockk<ConsentAnalyticsRepository>()
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val policyRepository = mockk<PolicyRepository>()
    private val rangeResolver =
        mockk<AnalyticsRangeResolver> {
            // The floor is resolved once and threaded into both resolve and priorWindow (one plan lookup per read).
            every { retentionFloor(userId) } returns floor
            every { resolve(any(), floor) } returns range
            // No comparable prior window by default; the baseline test overrides this.
            every { priorWindow(range, floor) } returns null
        }
    private val service =
        AnalyticsService(
            siteRepository,
            consentRepository,
            scanRepository,
            scanCookieRepository,
            policyRepository,
            rangeResolver,
            ConsentAnalyticsAssembler(),
        )

    private fun stubOwnedSiteWithEmptyCurrentWindow() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns
            SiteEntity(userId = userId, domain = "d.eu", siteKey = "k")
        every { consentRepository.dailyActionCounts(siteId, range.from, range.to) } returns emptyList()
        every { consentRepository.categoryOptInCounts(siteId, range.from, range.to) } returns emptyList()
        every { consentRepository.languageCounts(siteId, range.from, range.to) } returns emptyList()
        // No completed scan and no published policy — the consent path is what is under test.
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns null
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns null
    }

    @Test
    fun `attaches a prior-window consent baseline read from the shifted window`() {
        stubOwnedSiteWithEmptyCurrentWindow()
        every { rangeResolver.priorWindow(range, floor) } returns prior
        val priorDay = LocalDate.parse("2026-07-14")
        every { consentRepository.dailyActionCounts(siteId, prior.from, prior.to) } returns
            listOf(DailyActionCount(priorDay, "accept_all", 8), DailyActionCount(priorDay, "custom", 2))

        val result = service.summarize(userId, siteId, AnalyticsFilter())

        val previous = requireNotNull(result.previous)
        assertEquals(10, previous.totalEvents)
        assertEquals(8, previous.byAction.acceptAll)
        assertEquals(2, previous.byAction.custom)
        // The baseline reads the shifted prior window, never the current one.
        verify { consentRepository.dailyActionCounts(siteId, prior.from, prior.to) }
    }

    @Test
    fun `omits the baseline when no comparable prior window exists`() {
        stubOwnedSiteWithEmptyCurrentWindow()
        // priorWindow returns null (default stub): the prior read must not run at all.

        val result = service.summarize(userId, siteId, AnalyticsFilter())

        assertNull(result.previous)
        verify(exactly = 0) { consentRepository.dailyActionCounts(siteId, prior.from, prior.to) }
    }
}
