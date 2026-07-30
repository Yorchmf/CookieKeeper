package com.complyr.consent

import com.complyr.banner.BannerConfigEntity
import com.complyr.banner.BannerConfigService
import com.complyr.banner.DefaultBannerConfig
import com.complyr.common.IpHasher
import com.complyr.consent.dto.ConsentEventRequest
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentServiceTest {
    private val now: Instant = Instant.parse("2026-07-30T12:00:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val consentEventRepository = mockk<ConsentEventRepository>(relaxed = true)
    private val bannerConfigService = mockk<BannerConfigService>()
    private val ipHasher = IpHasher(Clock.fixed(now, ZoneOffset.UTC))
    private val service =
        ConsentService(siteRepository, consentEventRepository, bannerConfigService, ipHasher, Clock.fixed(now, ZoneOffset.UTC))

    private val siteKey = "pk_live_site_key"
    private val site = SiteEntity(userId = UUID.randomUUID(), domain = "example.com", siteKey = siteKey)

    private fun request(
        action: String = "accept_all",
        categories: Map<String, Boolean> = mapOf("necessary" to true, "statistics" to true),
        vid: String? = UUID.randomUUID().toString(),
        lang: String? = "de",
    ): ConsentEventRequest = ConsentEventRequest(siteKey = siteKey, action = action, categories = categories, lang = lang, vid = vid)

    private fun meta(
        ip: String? = "203.0.113.7",
        ua: String? = "Mozilla/5.0",
    ): ConsentRequestMeta = ConsentRequestMeta(clientIp = ip, userAgent = ua)

    private fun stubActiveSite() {
        every { siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE) } returns site
        // The site's published config declares the default taxonomy (necessary/preferences/
        // statistics/marketing) — the allow-list every test payload validates against.
        every { bannerConfigService.currentPublished(site.id) } returns
            BannerConfigEntity(siteId = site.id, version = DefaultBannerConfig.FIRST_VERSION, config = DefaultBannerConfig.document())
    }

    @Test
    fun `unknown or archived site key is a not-found and records nothing`() {
        every { siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE) } returns null

        assertThrows<UnknownSiteException> { service.record(request(), meta()) }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `unsupported action is rejected before any write`() {
        stubActiveSite()

        assertThrows<InvalidConsentPayloadException> { service.record(request(action = "sell_data"), meta()) }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `records an append-only event with hashed IP, trimmed UA and server timestamp`() {
        stubActiveSite()
        val vid = UUID.randomUUID()
        val saved = slot<ConsentEventEntity>()
        every { consentEventRepository.save(capture(saved)) } answers { firstArg() }

        service.record(request(vid = vid.toString()), meta())

        val event = saved.captured
        assertEquals(site.id, event.siteId)
        assertEquals(vid, event.visitorId, "cookie-stored vid becomes the visitor_id")
        assertEquals("accept_all", event.action)
        assertEquals(mapOf("necessary" to true, "statistics" to true), event.categories)
        assertEquals("de", event.lang)
        assertEquals(now, event.createdAt, "server stamps the audit time, not the client")
        // No raw PII reaches the audit log.
        assertNotEquals("203.0.113.7", event.ipHash)
        val ipHash = requireNotNull(event.ipHash) { "expected a hashed IP" }
        assertTrue(ipHash.matches(Regex("^[0-9a-f]{64}$")), ipHash)
        assertEquals("Mozilla/5.0", event.ua)
    }

    @Test
    fun `lang is lowercased and blank or whitespace-only lang becomes null`() {
        stubActiveSite()
        val saved = mutableListOf<ConsentEventEntity>()
        every { consentEventRepository.save(capture(saved)) } answers { firstArg() }

        service.record(request(lang = "  DE "), meta())
        service.record(request(lang = "   "), meta())
        service.record(request(lang = null), meta())

        assertEquals("de", saved[0].lang, "trimmed and lowercased")
        assertNull(saved[1].lang, "whitespace-only collapses to null")
        assertNull(saved[2].lang, "absent lang stays null")
    }

    @Test
    fun `user agent is trimmed to the max length and blank ua becomes null`() {
        stubActiveSite()
        val saved = mutableListOf<ConsentEventEntity>()
        every { consentEventRepository.save(capture(saved)) } answers { firstArg() }

        val overLong = "u".repeat(MAX_UA_LENGTH + 50)
        service.record(request(), meta(ua = overLong))
        service.record(request(), meta(ua = "   "))

        assertEquals(MAX_UA_LENGTH, saved[0].ua?.length, "UA capped at the max length")
        assertNull(saved[1].ua, "whitespace-only UA collapses to null")
    }

    @Test
    fun `a missing or malformed visitor id is replaced with a freshly minted uuid`() {
        stubActiveSite()
        val saved = mutableListOf<ConsentEventEntity>()
        every { consentEventRepository.save(capture(saved)) } answers { firstArg() }

        service.record(request(vid = null), meta())
        service.record(request(vid = "not-a-uuid"), meta())

        assertEquals(2, saved.size)
        // Each mint is a valid, distinct UUID — never the literal client string.
        assertNotEquals(saved[0].visitorId, saved[1].visitorId)
    }

    @Test
    fun `an over-long category key is rejected`() {
        stubActiveSite()
        val longKey = "x".repeat(ConsentEventRequest.MAX_CATEGORY_KEY_LENGTH + 1)

        assertThrows<InvalidConsentPayloadException> {
            service.record(request(categories = mapOf(longKey to true)), meta())
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a category the site never declared is rejected as forgery`() {
        stubActiveSite()

        assertThrows<InvalidConsentPayloadException> {
            service.record(request(categories = mapOf("necessary" to true, "fingerprinting" to true)), meta())
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a payload that rejects or omits a required category is refused`() {
        stubActiveSite()

        // necessary is required — it can never be false...
        assertThrows<InvalidConsentPayloadException> {
            service.record(request(categories = mapOf("necessary" to false, "statistics" to true)), meta())
        }
        // ...nor absent.
        assertThrows<InvalidConsentPayloadException> {
            service.record(request(categories = mapOf("statistics" to true)), meta())
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `absent network metadata still records with null ip hash and ua`() {
        stubActiveSite()
        val saved = slot<ConsentEventEntity>()
        every { consentEventRepository.save(capture(saved)) } answers { firstArg() }

        service.record(request(), meta(ip = null, ua = null))

        assertNull(saved.captured.ipHash)
        assertNull(saved.captured.ua)
    }

    private companion object {
        /** Mirrors the service's private UA cap; kept in sync by the trimming test above. */
        const val MAX_UA_LENGTH = 256
    }
}
