package com.complyr.policy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The public hosted read: language selection is forgiving so the page always renders something, and an
 * unknown or unpublished public id is one generic not-found (never an existence oracle). Repositories
 * are faked so this is a fast unit test of that resolution logic.
 */
class PolicyReadServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private val policyRepository = mockk<PolicyRepository>()
    private val policySettingsRepository = mockk<PolicySettingsRepository>()
    private val service = PolicyReadService(policyRepository, policySettingsRepository)

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

    private fun row(language: String): PolicyEntity =
        PolicyEntity(siteId = siteId, version = 4, language = language, html = "<section lang=\"$language\"/>", publishedAt = now)

    private fun stubVersion(vararg languages: String) {
        every { policySettingsRepository.findByPublicId(publicId) } returns settings()
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
        every { policySettingsRepository.findByPublicId(publicId) } returns settings()
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns null

        assertThrows<PolicyNotFoundException> { service.read(publicId, "en") }
    }
}
