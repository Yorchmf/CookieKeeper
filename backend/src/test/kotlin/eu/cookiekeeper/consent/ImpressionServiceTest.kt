package eu.cookiekeeper.consent

import eu.cookiekeeper.analytics.BannerImpressionRepository
import eu.cookiekeeper.consent.dto.ImpressionRequest
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
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

/**
 * Unit tests for [ImpressionService] (Track 4 Slice D). The service is deliberately the consent path's
 * simpler twin: validate the public site key to an ACTIVE site (or 404, no enumeration signal), then UPSERT a
 * bare (site, UTC-day) counter. What is proven here: the ACTIVE-only key check, the 404 for unknown/archived
 * keys, and that the recorded day is the server's own UTC calendar day (never a client-sent time), keyed on the
 * resolved site id. Repositories mocked — the SQL is [BannerImpressionRepository]'s own Testcontainers test.
 */
class ImpressionServiceTest {
    private val now: Instant = Instant.parse("2026-08-14T23:30:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val bannerImpressionRepository = mockk<BannerImpressionRepository>(relaxed = true)
    private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = ImpressionService(siteRepository, bannerImpressionRepository, fixedClock)

    private val siteKey = "pk_live_site_key"
    private val site = SiteEntity(userId = UUID.randomUUID(), domain = "example.eu", siteKey = siteKey)

    @Test
    fun `records an impression for the resolved active site on the server's UTC day`() {
        every { siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE) } returns site

        service.record(ImpressionRequest(siteKey = siteKey))

        // The counter is keyed on the resolved site id and the server's own UTC calendar day, matching how the
        // consent trend buckets created_at — so the interaction rate's numerator and denominator agree.
        verify(exactly = 1) { bannerImpressionRepository.increment(site.id, LocalDate.parse("2026-08-14")) }
    }

    @Test
    fun `an unknown or archived key is a 404 and records nothing`() {
        // findBySiteKeyAndStatus is ACTIVE-only: an unknown key and an archived site are indistinguishable here,
        // and neither records a counter (no enumeration signal, keys are public).
        every { siteRepository.findBySiteKeyAndStatus(any(), SiteStatus.ACTIVE) } returns null

        assertThrows<UnknownSiteException> { service.record(ImpressionRequest(siteKey = "pk_unknown")) }

        verify(exactly = 0) { bannerImpressionRepository.increment(any(), any()) }
    }
}
