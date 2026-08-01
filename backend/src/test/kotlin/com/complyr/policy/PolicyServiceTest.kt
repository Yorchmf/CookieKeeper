package com.complyr.policy

import com.complyr.banner.BannerConfigEntity
import com.complyr.banner.BannerConfigService
import com.complyr.common.ComplyrProperties
import com.complyr.policy.dto.PolicyGenerationRequest
import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanEntity
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.scan.ScanTrigger
import com.complyr.site.SiteEntity
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generation is where ownership scoping, version bumping, language resolution, and the stable-URL
 * guarantee live. Repositories/collaborators are faked so this stays a fast unit test of that logic;
 * the actual SQL and JSON-column mapping are covered by the API integration test.
 */
class PolicyServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val siteRepository = mockk<SiteRepository>()
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val policyRepository = mockk<PolicyRepository>(relaxUnitFun = true)
    private val policySettingsRepository = mockk<PolicySettingsRepository>()
    private val bannerConfigService = mockk<BannerConfigService>()
    private val properties = mockk<ComplyrProperties>()

    private val service =
        PolicyService(
            siteRepository,
            policyRepository,
            policySettingsRepository,
            bannerConfigService,
            PolicyContextBuilder(scanRepository, scanCookieRepository, clock),
            properties,
            clock,
        )

    private val userId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()

    private fun site(): SiteEntity = SiteEntity(id = siteId, userId = userId, domain = "acme.example.com", siteKey = "sk_test")

    private fun request(languages: List<String>? = null): PolicyGenerationRequest =
        PolicyGenerationRequest(
            companyName = "Acme GmbH",
            contactEmail = "privacy@acme.example.com",
            websiteUrl = null,
            address = null,
            languages = languages,
        )

    /** Wire the happy-path collaborators: owned site, no prior settings/version/banner/scan. */
    private fun stubHappyPath() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { policySettingsRepository.findById(siteId) } returns Optional.empty()
        every { policySettingsRepository.save(any()) } answers { firstArg() }
        every { policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId) } returns null
        every { policyRepository.save(any()) } answers { firstArg() }
        every { policyRepository.acquireSiteGenerationLock(any()) } returns 1L
        every { bannerConfigService.currentPublished(siteId) } returns null
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns null
        every { properties.appBaseUrl } returns "https://app.complyr.eu"
    }

    @Test
    fun `generate with no language hint publishes all five supported languages and a stable hosted url`() {
        stubHappyPath()

        val response = service.generate(userId, siteId, request())

        assertEquals(PolicyLanguages.SUPPORTED, response.languages)
        verify(exactly = PolicyLanguages.SUPPORTED.size) { policyRepository.save(any()) }
        // The per-site advisory lock is taken so concurrent generations serialize on the version bump.
        verify(exactly = 1) { policyRepository.acquireSiteGenerationLock(any()) }
        assertTrue(
            response.hostedUrl == "https://app.complyr.eu/p/${response.publicId}",
            message = "hosted url is appBaseUrl + /p/{publicId}",
        )
    }

    @Test
    fun `explicit languages narrow the publish set and unsupported entries are dropped`() {
        stubHappyPath()
        val saved = mutableListOf<PolicyEntity>()
        every { policyRepository.save(capture(saved)) } answers { firstArg() }

        val response = service.generate(userId, siteId, request(languages = listOf("EN", "xx", "de-DE", "en")))

        // en + de survive (normalized, de-duped); xx is unsupported and dropped.
        assertEquals(listOf("en", "de"), response.languages)
        assertEquals(listOf("en", "de"), saved.map { it.language })
    }

    @Test
    fun `explicit languages that are all unsupported is a 400, not a silent empty publish`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()

        assertThrows<UnsupportedPolicyLanguageException> {
            service.generate(userId, siteId, request(languages = listOf("xx", "zz")))
        }
        verify(exactly = 0) { policyRepository.save(any()) }
    }

    @Test
    fun `absent language hint falls back to the site's banner languages`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { policySettingsRepository.findById(siteId) } returns Optional.empty()
        every { policySettingsRepository.save(any()) } answers { firstArg() }
        every { policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId) } returns null
        every { policyRepository.save(any()) } answers { firstArg() }
        every { policyRepository.acquireSiteGenerationLock(any()) } returns 1L
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns null
        every { properties.appBaseUrl } returns "https://app.complyr.eu"
        val banner = mockk<BannerConfigEntity>()
        every { banner.config.languages } returns listOf("fr", "es")
        every { bannerConfigService.currentPublished(siteId) } returns banner

        val response = service.generate(userId, siteId, request())

        assertEquals(listOf("fr", "es"), response.languages)
    }

    @Test
    fun `generate for a foreign site is an ownership miss and never writes`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns null

        assertThrows<SiteNotFoundException> { service.generate(userId, siteId, request()) }
        verify(exactly = 0) { policyRepository.save(any()) }
        verify(exactly = 0) { policySettingsRepository.save(any()) }
    }

    @Test
    fun `regenerating bumps the version above the latest existing one`() {
        stubHappyPath()
        val existing = PolicyEntity(siteId = siteId, version = 3, language = "en", html = "<p/>", publishedAt = now)
        every { policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId) } returns existing
        val saved = mutableListOf<PolicyEntity>()
        every { policyRepository.save(capture(saved)) } answers { firstArg() }

        val response = service.generate(userId, siteId, request(languages = listOf("en")))

        assertEquals(4, response.version)
        assertEquals(4, saved.single().version)
    }

    @Test
    fun `republishing reuses existing settings so the public id stays stable`() {
        val stablePublicId = UUID.randomUUID()
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { policySettingsRepository.findById(siteId) } returns
            Optional.of(
                PolicySettingsEntity(
                    siteId = siteId,
                    publicId = stablePublicId,
                    details = PolicyDetails("Old Name", "old@acme.example.com", "https://acme.example.com"),
                    createdAt = now.minusSeconds(3600),
                    updatedAt = now.minusSeconds(3600),
                ),
            )
        val savedSettings = slot<PolicySettingsEntity>()
        every { policySettingsRepository.save(capture(savedSettings)) } answers { firstArg() }
        every { policyRepository.findFirstBySiteIdOrderByVersionDesc(siteId) } returns null
        every { policyRepository.save(any()) } answers { firstArg() }
        every { policyRepository.acquireSiteGenerationLock(any()) } returns 1L
        every { bannerConfigService.currentPublished(siteId) } returns null
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns null
        every { properties.appBaseUrl } returns "https://app.complyr.eu"

        val response = service.generate(userId, siteId, request(languages = listOf("en")))

        assertEquals(stablePublicId.toString(), response.publicId)
        // Details refreshed, but the stable id and creation stamp are carried through untouched.
        assertEquals("Acme GmbH", savedSettings.captured.details.companyName)
        assertEquals(stablePublicId, savedSettings.captured.publicId)
        assertEquals(now.minusSeconds(3600), savedSettings.captured.createdAt)
    }

    @Test
    fun `generate sources cookies from the latest done scan, splitting classified from needs-review`() {
        stubHappyPath()
        val scan =
            ScanEntity(
                siteId = siteId,
                status = ScanStatus.DONE,
                trigger = ScanTrigger.MANUAL,
                createdAt = now,
                updatedAt = now,
            )
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns scan
        every { scanCookieRepository.findByScanId(scan.id) } returns
            listOf(
                ScanCookieEntity(scanId = scan.id, name = "_ga", category = "statistics", provider = "Google", isKnown = true),
                ScanCookieEntity(scanId = scan.id, name = "mystery", category = null, isKnown = false),
            )
        val saved = slot<PolicyEntity>()
        every { policyRepository.save(capture(saved)) } answers { firstArg() }

        service.generate(userId, siteId, request(languages = listOf("en")))

        // The classified cookie lands in its category table; the unknown one is surfaced, never dropped.
        val html = saved.captured.html
        assertTrue(html.contains("_ga"), message = "classified cookie is listed")
        assertTrue(html.contains("mystery"), message = "unclassified cookie is still disclosed")
    }

    @Test
    fun `current throws not-found until a policy has been generated`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { policySettingsRepository.findById(siteId) } returns Optional.empty()

        assertThrows<PolicyNotFoundException> { service.current(userId, siteId) }
    }

    @Test
    fun `current returns the latest published version with its languages sorted`() {
        val publicId = UUID.randomUUID()
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { policySettingsRepository.findById(siteId) } returns
            Optional.of(
                PolicySettingsEntity(
                    siteId = siteId,
                    publicId = publicId,
                    details = PolicyDetails("Acme GmbH", "privacy@acme.example.com", "https://acme.example.com"),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val latest = PolicyEntity(siteId = siteId, version = 2, language = "en", html = "<p/>", publishedAt = now)
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns latest
        every { policyRepository.findBySiteIdAndVersion(siteId, 2) } returns
            listOf(
                PolicyEntity(siteId = siteId, version = 2, language = "fr", html = "<p/>", publishedAt = now),
                PolicyEntity(siteId = siteId, version = 2, language = "de", html = "<p/>", publishedAt = now),
            )
        every { properties.appBaseUrl } returns "https://app.complyr.eu"

        val response = service.current(userId, siteId)

        assertEquals(2, response.version)
        assertEquals(listOf("de", "fr"), response.languages)
        assertEquals("https://app.complyr.eu/p/$publicId", response.hostedUrl)
    }
}
