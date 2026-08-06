package com.complyr.policy

import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import com.complyr.site.VerificationMethod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The hosted read: language selection is forgiving so the page always renders something, and every
 * refusal is one generic not-found (never an existence oracle). The verification gate (ADR-17) lives
 * here too — an unverified customer must not publish a Complyr-hosted page for a domain they haven't
 * proved they control. Repositories are faked so this is a fast unit test of that resolution logic.
 */
class PolicyReadServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private val policyRepository = mockk<PolicyRepository>()
    private val policySettingsRepository = mockk<PolicySettingsRepository>()
    private val siteRepository = mockk<SiteRepository>()
    private val service = PolicyReadService(policyRepository, policySettingsRepository, siteRepository)

    private val siteId = UUID.randomUUID()
    private val publicId = UUID.randomUUID()

    private fun settings(): PolicySettingsEntity =
        PolicySettingsEntity(
            siteId = siteId,
            publicId = publicId,
            details = PolicyDetails("Acme GmbH", "privacy@acme.example.com", "https://acme.example.com"),
            createdAt = now,
            updatedAt = now,
        )

    private fun site(
        status: SiteStatus = SiteStatus.ACTIVE,
        verifiedAt: Instant? = now,
    ): SiteEntity =
        SiteEntity(
            id = siteId,
            userId = UUID.randomUUID(),
            domain = "acme.example.com",
            siteKey = "pk_test",
            status = status,
            verifiedAt = verifiedAt,
            verificationMethod = verifiedAt?.let { VerificationMethod.SNIPPET },
            createdAt = now,
            updatedAt = now,
        )

    private fun row(language: String): PolicyEntity =
        PolicyEntity(siteId = siteId, version = 4, language = language, html = "<section lang=\"$language\"/>", publishedAt = now)

    private fun stubVersion(vararg languages: String) {
        every { policySettingsRepository.findByPublicId(publicId) } returns settings()
        every { siteRepository.findById(siteId) } returns Optional.of(site())
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns row("en")
        every { policyRepository.findBySiteIdAndVersion(siteId, 4) } returns languages.map(::row)
    }

    @Test
    fun `the requested language is served when the published version has it`() {
        stubVersion("en", "de", "fr")

        val response = service.read(publicId, "de")

        assertEquals("de", response.language)
        assertEquals(listOf("de", "en", "fr"), response.availableLanguages)
        assertEquals("Acme GmbH", response.companyName)
        assertEquals(4, response.version)
    }

    @Test
    fun `a normalized region tag resolves to its base language`() {
        stubVersion("en", "de")

        assertEquals("de", service.read(publicId, "de-AT").language)
    }

    @Test
    fun `an absent language falls back to the default language`() {
        stubVersion("en", "de")

        assertEquals("en", service.read(publicId, null).language)
    }

    @Test
    fun `a requested language not in this version falls back to the default`() {
        stubVersion("en", "de")

        assertEquals("en", service.read(publicId, "it").language)
    }

    @Test
    fun `when the default is absent it falls back to any available language deterministically`() {
        stubVersion("fr", "de")

        // No requested match, no default (en); the lowest code wins for a stable choice.
        assertEquals("de", service.read(publicId, "it").language)
    }

    @Test
    fun `an unknown public id is a generic not-found`() {
        every { policySettingsRepository.findByPublicId(publicId) } returns null

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
        verify(exactly = 0) { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(any()) }
    }

    @Test
    fun `settings exist but nothing is published yields the same generic not-found`() {
        stubVersion("en")
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns null

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
    }

    @Test
    fun `an unverified site's published policy is not served publicly`() {
        stubVersion("en", "de")
        every { siteRepository.findById(siteId) } returns Optional.of(site(verifiedAt = null))

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
        // The gate answers before any policy row is read — the refusal can't depend on publish state.
        verify(exactly = 0) { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(any()) }
    }

    @Test
    fun `an archived site's published policy is not served publicly`() {
        stubVersion("en", "de")
        every { siteRepository.findById(siteId) } returns Optional.of(site(status = SiteStatus.ARCHIVED))

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
    }

    @Test
    fun `a settings row whose site is gone is not served publicly`() {
        stubVersion("en")
        every { siteRepository.findById(siteId) } returns Optional.empty()

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
    }

    @Test
    fun `every refusal is the identical exception type and code, so the public id is never an oracle`() {
        // The whole anti-oracle argument in one assertion: unknown id, unverified, archived, site gone
        // and nothing-published must be indistinguishable to a caller holding a public id.
        val unknownId =
            run {
                every { policySettingsRepository.findByPublicId(publicId) } returns null
                assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
            }

        val refusals =
            listOf(
                {
                    stubVersion("en")
                    every { siteRepository.findById(siteId) } returns Optional.of(site(verifiedAt = null))
                },
                {
                    stubVersion("en")
                    every { siteRepository.findById(siteId) } returns Optional.of(site(status = SiteStatus.ARCHIVED))
                },
                {
                    stubVersion("en")
                    every { siteRepository.findById(siteId) } returns Optional.empty()
                },
                {
                    stubVersion("en")
                    every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns null
                },
            )
        refusals.forEach { arrange ->
            arrange()
            val thrown = assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
            assertEquals(unknownId::class, thrown::class)
            assertEquals(unknownId.code, thrown.code)
            assertEquals(unknownId.status, thrown.status)
            assertEquals(unknownId.message, thrown.message)
        }
    }

    @Test
    fun `readBySite serves a published policy without consulting the site at all`() {
        // The preview path's primitive: ownership is the caller's gate, so this must not re-apply the
        // public verification gate or the owner could never preview before verifying.
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns row("en")
        every { policyRepository.findBySiteIdAndVersion(siteId, 4) } returns listOf(row("en"), row("de"))

        val response = service.readBySite(settings(), "de")

        assertEquals("de", response.language)
        verify(exactly = 0) { siteRepository.findById(any()) }
    }
}
