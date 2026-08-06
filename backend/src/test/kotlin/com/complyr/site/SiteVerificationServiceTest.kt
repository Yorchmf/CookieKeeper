package com.complyr.site

import io.mockk.CapturingSlot
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteVerificationServiceTest {
    private val now: Instant = Instant.parse("2026-08-04T09:00:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val fetcher = mockk<SiteVerificationFetcher>()
    private val dnsTxtLookup = mockk<DnsTxtLookup>()
    private val cdnHost = mockk<CdnHost>()

    private val service =
        SiteVerificationService(
            siteRepository,
            fetcher,
            dnsTxtLookup,
            cdnHost,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val userId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()
    private val siteKey = "pk_AbC123"

    private val snippetHtml =
        """<html><head><script async src="https://cdn.complyr.eu/v1.js" data-complyr="$siteKey"></script></head></html>"""

    init {
        every { cdnHost.value } returns "cdn.complyr.eu"
    }

    private fun site(
        status: SiteStatus = SiteStatus.ACTIVE,
        verifiedAt: Instant? = null,
        verificationMethod: VerificationMethod? = null,
    ) = SiteEntity(
        id = siteId,
        userId = userId,
        domain = "example.com",
        siteKey = siteKey,
        status = status,
        verifiedAt = verifiedAt,
        verificationMethod = verificationMethod,
        createdAt = now,
        updatedAt = now,
    )

    private fun stubSite(site: SiteEntity?) {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site
    }

    private fun captureSaved(): CapturingSlot<SiteEntity> {
        val saved = slot<SiteEntity>()
        every { siteRepository.save(capture(saved)) } answers { saved.captured }
        return saved
    }

    @Test
    fun `snippet found on the homepage verifies and records SNIPPET`() {
        stubSite(site())
        every { fetcher.fetchHomepage("example.com") } returns snippetHtml
        val saved = captureSaved()

        val response = service.verify(userId, siteId)

        assertTrue(response.verified)
        assertEquals(now, response.verifiedAt)
        assertEquals("snippet", response.method)
        assertNull(response.reason)
        assertEquals(now, saved.captured.verifiedAt)
        assertEquals(VerificationMethod.SNIPPET, saved.captured.verificationMethod)
        assertEquals(now, saved.captured.updatedAt)
        // The cheap check is the only one paid for when it hits.
        verify(exactly = 0) { dnsTxtLookup.hasSiteKeyRecord(any(), any()) }
    }

    @Test
    fun `falls back to the TXT record when the homepage has no snippet`() {
        stubSite(site())
        every { fetcher.fetchHomepage("example.com") } returns "<html><body>nothing here</body></html>"
        every { dnsTxtLookup.hasSiteKeyRecord("example.com", siteKey) } returns true
        val saved = captureSaved()

        val response = service.verify(userId, siteId)

        assertTrue(response.verified)
        assertEquals("dns_txt", response.method)
        assertEquals(VerificationMethod.DNS_TXT, saved.captured.verificationMethod)
    }

    @Test
    fun `an unreachable homepage still verifies via the TXT record`() {
        // The two proofs are independent: a customer who only ever intends to use DNS should not be
        // blocked by a homepage that refuses our fetcher (WAF, 403, redirect off-family).
        stubSite(site())
        every { fetcher.fetchHomepage("example.com") } returns null
        every { dnsTxtLookup.hasSiteKeyRecord("example.com", siteKey) } returns true
        captureSaved()

        val response = service.verify(userId, siteId)

        assertTrue(response.verified)
        assertEquals("dns_txt", response.method)
    }

    @Test
    fun `reports snippet_not_found when the page was read but carried no proof`() {
        stubSite(site())
        every { fetcher.fetchHomepage("example.com") } returns "<html><body>nothing here</body></html>"
        every { dnsTxtLookup.hasSiteKeyRecord("example.com", siteKey) } returns false

        val response = service.verify(userId, siteId)

        assertFalse(response.verified)
        assertEquals("snippet_not_found", response.reason)
        assertNull(response.method)
        assertNull(response.verifiedAt)
        verify(exactly = 0) { siteRepository.save(any()) }
    }

    @Test
    fun `reports unreachable when the homepage could not be read and DNS misses`() {
        // Every fetch failure — SSRF refusal, DNS failure, timeout, 500, wrong content type — collapses
        // here. Anything finer would make the endpoint an internal-network mapping oracle.
        stubSite(site())
        every { fetcher.fetchHomepage("example.com") } returns null
        every { dnsTxtLookup.hasSiteKeyRecord("example.com", siteKey) } returns false

        val response = service.verify(userId, siteId)

        assertFalse(response.verified)
        assertEquals("unreachable", response.reason)
        verify(exactly = 0) { siteRepository.save(any()) }
    }

    @Test
    fun `an already-verified site is idempotent and makes no outbound request`() {
        // Not just an optimisation: without this, a verified site is a repeatable authenticated way to
        // make the api container dial a host of the caller's choosing.
        val verifiedAt = now.minusSeconds(3600)
        stubSite(site(verifiedAt = verifiedAt, verificationMethod = VerificationMethod.DNS_TXT))

        val response = service.verify(userId, siteId)

        assertTrue(response.verified)
        assertEquals(verifiedAt, response.verifiedAt)
        assertEquals("dns_txt", response.method)
        verify(exactly = 0) { fetcher.fetchHomepage(any()) }
        verify(exactly = 0) { dnsTxtLookup.hasSiteKeyRecord(any(), any()) }
        verify(exactly = 0) { siteRepository.save(any()) }
    }

    @Test
    fun `another user's site is a 404, never a 403`() {
        stubSite(null)

        assertThrows<SiteNotFoundException> { service.verify(userId, siteId) }
        verify(exactly = 0) { fetcher.fetchHomepage(any()) }
    }

    @Test
    fun `an archived site is a 404 — it has no widget and nothing to activate`() {
        stubSite(site(status = SiteStatus.ARCHIVED))

        assertThrows<SiteNotFoundException> { service.verify(userId, siteId) }
        verify(exactly = 0) { fetcher.fetchHomepage(any()) }
    }
}
