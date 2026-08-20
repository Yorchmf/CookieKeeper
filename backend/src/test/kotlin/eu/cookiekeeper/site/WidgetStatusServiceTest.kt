package eu.cookiekeeper.site

import eu.cookiekeeper.analytics.BannerImpressionRepository
import eu.cookiekeeper.analytics.WidgetActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three-state widget-status verdict: never installed, seen recently, or gone quiet. The states are
 * what the site page's copy branches on, so each boundary — in particular that the window is inclusive of
 * both today and the day exactly [WidgetStatusService.ACTIVE_WINDOW_DAYS] - 1 back — is pinned here.
 */
class WidgetStatusServiceTest {
    // A fixed "now" late in the UTC day, so a naive local-timezone read would land on a different date.
    private val now: Instant = Instant.parse("2026-08-18T23:30:00Z")
    private val today: LocalDate = LocalDate.parse("2026-08-18")

    private val siteRepository = mockk<SiteRepository>()
    private val bannerImpressionRepository = mockk<BannerImpressionRepository>()

    private val service =
        WidgetStatusService(siteRepository, bannerImpressionRepository, Clock.fixed(now, ZoneOffset.UTC))

    private val userId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()

    private fun ownedSite() =
        SiteEntity(
            id = siteId,
            userId = userId,
            domain = "example.com",
            siteKey = "pk_AbC123",
            createdAt = now,
            updatedAt = now,
        )

    private fun givenActivity(activity: WidgetActivity) {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns ownedSite()
        every { bannerImpressionRepository.widgetActivity(siteId, any(), any()) } returns activity
    }

    @Test
    fun `a site that never recorded an impression is NEVER_SEEN`() {
        givenActivity(WidgetActivity(lastDay = null, today = 0, window = 0))

        val status = service.status(userId, siteId)

        assertEquals("never_seen", status.state)
        assertNull(status.lastSeenDay)
        assertEquals(0, status.impressionsToday)
        assertEquals(0, status.impressionsInWindow)
    }

    @Test
    fun `an impression today is ACTIVE and reports the counts`() {
        givenActivity(WidgetActivity(lastDay = today, today = 12, window = 40))

        val status = service.status(userId, siteId)

        assertEquals("active", status.state)
        assertEquals(today, status.lastSeenDay)
        assertEquals(12, status.impressionsToday)
        assertEquals(40, status.impressionsInWindow)
        assertEquals(WidgetStatusService.ACTIVE_WINDOW_DAYS, status.windowDays)
    }

    @Test
    fun `the window is inclusive of its oldest day and excludes the day before it`() {
        // 7-day window ending today (Aug 18) starts Aug 12 — today plus the six days before it.
        givenActivity(WidgetActivity(lastDay = LocalDate.parse("2026-08-12"), today = 0, window = 3))
        assertEquals("active", service.status(userId, siteId).state)

        givenActivity(WidgetActivity(lastDay = LocalDate.parse("2026-08-11"), today = 0, window = 0))
        assertEquals("idle", service.status(userId, siteId).state)
    }

    @Test
    fun `the day range is computed in UTC, matching how the counter is stamped`() {
        givenActivity(WidgetActivity(lastDay = today, today = 1, window = 1))

        service.status(userId, siteId)

        // 23:30Z on Aug 18 is Aug 18 in UTC (and Aug 19 in, say, CEST) — the query must ask for the UTC day
        // the beacon wrote, or a late-evening impression would read as "not seen today".
        verify {
            bannerImpressionRepository.widgetActivity(siteId, today, LocalDate.parse("2026-08-12"))
        }
    }

    @Test
    fun `another account's site is a 404, never another account's activity`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns null

        assertThrows<SiteNotFoundException> { service.status(userId, siteId) }

        verify(exactly = 0) { bannerImpressionRepository.widgetActivity(any(), any(), any()) }
    }
}
