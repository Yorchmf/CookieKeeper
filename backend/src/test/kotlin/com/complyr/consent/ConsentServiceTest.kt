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
import java.time.Duration
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
    private val consentIdempotencyRepository = mockk<ConsentIdempotencyRepository>(relaxed = true)
    private val bannerConfigService = mockk<BannerConfigService>()
    private val ipHasher = IpHasher(Clock.fixed(now, ZoneOffset.UTC))
    private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
    private val originToken =
        ConsentOriginToken(
            secret = "test-only-consent-origin-token-secret-0123456789",
            ttl = Duration.ofMinutes(2),
            clock = fixedClock,
        )
    private val service =
        ConsentService(
            siteRepository,
            consentEventRepository,
            consentIdempotencyRepository,
            bannerConfigService,
            ipHasher,
            originToken,
            fixedClock,
        )

    private val siteKey = "pk_live_site_key"
    private val site = SiteEntity(userId = UUID.randomUUID(), domain = "example.com", siteKey = siteKey)

    private fun request(
        action: String = "accept_all",
        categories: Map<String, Boolean> = mapOf("necessary" to true, "statistics" to true),
        vid: String? = UUID.randomUUID().toString(),
        lang: String? = "de",
        eventKey: String? = null,
        originToken: String? = null,
    ): ConsentEventRequest =
        ConsentEventRequest(
            siteKey = siteKey,
            action = action,
            categories = categories,
            lang = lang,
            vid = vid,
            eventKey = eventKey,
            originToken = originToken,
        )

    private fun meta(
        ip: String? = "203.0.113.7",
        ua: String? = "Mozilla/5.0",
        origin: String? = null,
    ): ConsentRequestMeta = ConsentRequestMeta(clientIp = ip, userAgent = ua, origin = origin)

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
    fun `a replayed event key records the consent once and skips the duplicate`() {
        stubActiveSite()
        val key = UUID.randomUUID()
        // First claim wins (1 row inserted); the replayed retry conflicts (0 rows).
        every { consentIdempotencyRepository.claim(key) } returns 1 andThen 0

        service.record(request(eventKey = key.toString()), meta())
        service.record(request(eventKey = key.toString()), meta())

        verify(exactly = 2) { consentIdempotencyRepository.claim(key) }
        verify(exactly = 1) { consentEventRepository.save(any()) }
    }

    @Test
    fun `an absent or malformed event key skips the idempotency claim and still records`() {
        stubActiveSite()

        service.record(request(eventKey = null), meta())
        service.record(request(eventKey = "not-a-uuid"), meta())

        // No dedupe attempt for a missing/garbled key, but the event is never dropped.
        verify(exactly = 0) { consentIdempotencyRepository.claim(any()) }
        verify(exactly = 2) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a valid origin token whose origin matches the request records the event`() {
        stubActiveSite()
        val origin = "https://example.com"
        val token = originToken.mint(siteKey, origin).token

        service.record(request(originToken = token), meta(origin = origin))

        verify(exactly = 1) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a present but invalid origin token is rejected before any write`() {
        stubActiveSite()

        // A garbage token is present, so it must be enforced (unlike an absent one).
        assertThrows<InvalidConsentTokenException> {
            service.record(request(originToken = "not.a.valid.token"), meta(origin = "https://example.com"))
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `an origin token minted for another origin is rejected`() {
        stubActiveSite()
        // Token bound to the real page origin, but the consent POST arrives with a different Origin —
        // the replay-from-elsewhere case the token exists to stop.
        val token = originToken.mint(siteKey, "https://example.com").token

        assertThrows<InvalidConsentTokenException> {
            service.record(request(originToken = token), meta(origin = "https://evil.example"))
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a token minted for a different site key is rejected`() {
        stubActiveSite()
        val token = originToken.mint("pk_live_other_site", "https://example.com").token

        assertThrows<InvalidConsentTokenException> {
            service.record(request(originToken = token), meta(origin = "https://example.com"))
        }
        verify(exactly = 0) { consentEventRepository.save(any()) }
    }

    @Test
    fun `a tokenless request is still recorded so no audit evidence is ever lost`() {
        stubActiveSite()

        // No token and no origin — an old widget, a privacy browser, or a delayed retry.
        service.record(request(originToken = null), meta(origin = null))

        verify(exactly = 1) { consentEventRepository.save(any()) }
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
