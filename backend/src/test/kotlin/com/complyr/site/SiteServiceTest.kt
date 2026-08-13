package com.complyr.site

import com.complyr.auth.EmailNotVerifiedException
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.banner.BannerConfigService
import com.complyr.billing.EntitlementService
import com.complyr.billing.SiteLimitReachedException
import com.complyr.common.ComplyrProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiteServiceTest {
    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.complyr.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val now: Instant = Instant.parse("2026-07-28T12:00:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val userRepository = mockk<UserRepository>()
    private val bannerConfigService = mockk<BannerConfigService>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)

    // Relaxed: requireCanAddSite is a no-op by default, so every existing happy-path create stays
    // green; the cap-reached test below overrides it to throw.
    private val entitlementService = mockk<EntitlementService>(relaxed = true)
    private val service =
        SiteService(
            siteRepository,
            userRepository,
            entitlementService,
            bannerConfigService,
            properties,
            events,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val userId: UUID = UUID.randomUUID()

    private fun stubUser(verifiedAt: Instant? = now) {
        every { userRepository.findById(userId) } returns
            Optional.of(
                UserEntity(id = userId, email = "a@example.com", passwordHash = "hash", verifiedAt = verifiedAt),
            )
    }

    private fun site(
        domain: String = "example.com",
        status: SiteStatus = SiteStatus.ACTIVE,
    ): SiteEntity = SiteEntity(userId = userId, domain = domain, siteKey = "pk_key", status = status)

    @Test
    fun `unverified users cannot create sites`() {
        stubUser(verifiedAt = null)

        assertThrows<EmailNotVerifiedException> { service.create(userId, "example.com") }
    }

    @Test
    fun `create normalizes the domain and generates a pk_ site key`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "foo.example.com", SiteStatus.ACTIVE) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        val response = service.create(userId, "HTTPS://Foo.Example.COM/path")

        assertEquals("foo.example.com", response.domain)
        assertTrue(saved.captured.siteKey.matches(Regex("^pk_[A-Za-z0-9]{32}$")), "bad key: ${saved.captured.siteKey}")
        assertEquals("active", response.status)
    }

    @Test
    fun `create seeds the default banner config for the new site`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        service.create(userId, "example.com")

        verify(exactly = 1) { bannerConfigService.createDefaultFor(saved.captured.id) }
    }

    @Test
    fun `create publishes a SiteCreatedEvent to trigger the first scan`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        service.create(userId, "example.com")

        verify(exactly = 1) { events.publishEvent(SiteCreatedEvent(saved.captured.id)) }
    }

    @Test
    fun `create is rejected when the plan site cap is reached`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        every { entitlementService.requireCanAddSite(userId) } throws SiteLimitReachedException()

        assertThrows<SiteLimitReachedException> { service.create(userId, "example.com") }

        // The cap guard fires before any write — no site row, banner, or scan is created.
        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `create rejects an active duplicate domain`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns true

        assertThrows<DomainAlreadyRegisteredException> { service.create(userId, "example.com") }
    }

    @Test
    fun `create maps a domain unique-index race to the same conflict error`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } throws domainConflict()

        assertThrows<DomainAlreadyRegisteredException> { service.create(userId, "example.com") }
    }

    @Test
    fun `create rethrows integrity violations of other constraints instead of mislabeling them`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } throws
            integrityViolation(constraintName = "uq_sites_site_key")

        assertThrows<DataIntegrityViolationException> { service.create(userId, "example.com") }
    }

    @Test
    fun `create rethrows integrity violations without an identifiable constraint`() {
        stubUser()
        every { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } throws DataIntegrityViolationException("dup")

        assertThrows<DataIntegrityViolationException> { service.create(userId, "example.com") }
    }

    @Test
    fun `create rejects invalid domains before touching the repository`() {
        stubUser()

        assertThrows<InvalidDomainException> { service.create(userId, "192.168.0.1") }

        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `archived domains can be re-registered`() {
        stubUser()
        // exists checks ACTIVE only — an archived row for the same domain does not block.
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } answers { firstArg() }

        val response = service.create(userId, "example.com")

        assertEquals("example.com", response.domain)
    }

    @Test
    fun `archive is a soft status change, never a delete`() {
        val existing = site()
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        val saved = slot<SiteEntity>()
        every { siteRepository.save(capture(saved)) } answers { firstArg() }

        service.archive(userId, existing.id)

        assertEquals(SiteStatus.ARCHIVED, saved.captured.status)
        verify(exactly = 0) { siteRepository.delete(any()) }
        verify(exactly = 0) { siteRepository.deleteById(any()) }
    }

    @Test
    fun `cross-user access looks like a plain not-found`() {
        val foreignSiteId = UUID.randomUUID()
        every { siteRepository.findByIdAndUserId(foreignSiteId, userId) } returns null

        assertThrows<SiteNotFoundException> { service.get(userId, foreignSiteId) }
        assertThrows<SiteNotFoundException> { service.update(userId, foreignSiteId, "new.example.com") }
        assertThrows<SiteNotFoundException> { service.archive(userId, foreignSiteId) }
        assertThrows<SiteNotFoundException> { service.restore(userId, foreignSiteId) }
        assertThrows<SiteNotFoundException> { service.setBrandingPreference(userId, foreignSiteId, hideBranding = false) }
    }

    @Test
    fun `restore reactivates an archived site and stamps updatedAt`() {
        val existing = site(status = SiteStatus.ARCHIVED)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        val detail = service.restore(userId, existing.id)

        assertEquals(SiteStatus.ACTIVE, saved.captured.status)
        assertEquals(now, saved.captured.updatedAt)
        assertEquals("active", detail.status)
        // Restore is a reactivation, not a create: the banner config survived archival and the rescan job
        // picks up ACTIVE sites on its own, so neither create-only side effect must fire again.
        verify(exactly = 0) { bannerConfigService.createDefaultFor(any()) }
        verify(exactly = 0) { events.publishEvent(any<SiteCreatedEvent>()) }
    }

    @Test
    fun `restore preserves verification state — archiving never disproved control`() {
        val existing =
            site(status = SiteStatus.ARCHIVED)
                .copy(verifiedAt = now.minusSeconds(3600), verificationMethod = VerificationMethod.DNS_TXT)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        service.restore(userId, existing.id)

        assertEquals(now.minusSeconds(3600), saved.captured.verifiedAt)
        assertEquals(VerificationMethod.DNS_TXT, saved.captured.verificationMethod)
    }

    @Test
    fun `restore is an idempotent no-op for an already-active site`() {
        val existing = site(status = SiteStatus.ACTIVE)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing

        val detail = service.restore(userId, existing.id)

        assertEquals("active", detail.status)
        // No write, and the cap/domain guards never run — re-restoring an active site must not 403 or 409.
        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
        verify(exactly = 0) { entitlementService.requireCanAddSite(any()) }
        verify(exactly = 0) { siteRepository.existsByUserIdAndDomainAndStatus(any(), any(), any()) }
    }

    @Test
    fun `restore is rejected when the domain was re-registered while archived`() {
        val existing = site(status = SiteStatus.ARCHIVED)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns true

        assertThrows<DomainAlreadyRegisteredException> { service.restore(userId, existing.id) }

        // Domain conflict is decided before the cap guard and before any write.
        verify(exactly = 0) { entitlementService.requireCanAddSite(any()) }
        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `restore is rejected when the plan site cap is reached`() {
        val existing = site(status = SiteStatus.ARCHIVED)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        every { entitlementService.requireCanAddSite(userId) } throws SiteLimitReachedException()

        assertThrows<SiteLimitReachedException> { service.restore(userId, existing.id) }

        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `restore maps a domain unique-index race to the same conflict error`() {
        val existing = site(status = SiteStatus.ARCHIVED)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "example.com", SiteStatus.ACTIVE) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } throws domainConflict()

        assertThrows<DomainAlreadyRegisteredException> { service.restore(userId, existing.id) }
    }

    @Test
    fun `setBrandingPreference persists the changed preference and stamps updatedAt`() {
        // A site defaults to hide_branding=true; flipping it to false is a real change and must persist.
        val existing = site()
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        val saved = slot<SiteEntity>()
        every { siteRepository.save(capture(saved)) } answers { firstArg() }

        val detail = service.setBrandingPreference(userId, existing.id, hideBranding = false)

        assertEquals(false, saved.captured.hideBranding)
        assertEquals(now, saved.captured.updatedAt)
        assertEquals(false, detail.hideBranding, "the response echoes the stored preference")
    }

    @Test
    fun `setBrandingPreference is a no-op that never writes when the preference is unchanged`() {
        // The site already hides branding (the default); re-asserting it must not bump updatedAt or churn
        // a row — an idempotent re-toggle should be free.
        val existing = site()
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing

        val detail = service.setBrandingPreference(userId, existing.id, hideBranding = true)

        assertEquals(true, detail.hideBranding)
        verify(exactly = 0) { siteRepository.save(any<SiteEntity>()) }
        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `detail exposes whether the plan entitles branding removal, independent of the stored wish`() {
        // brandingRemovalEntitled is the plan capability (best-effort billing read), separate from the
        // site's own hide_branding preference — the dashboard needs both to render the gated toggle.
        val existing = site()
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { entitlementService.removeBrandingOrDefault(userId) } returns true

        val detail = service.get(userId, existing.id)

        assertEquals(true, detail.brandingRemovalEntitled)
        assertEquals(true, detail.hideBranding, "the default stored wish is surfaced too")
    }

    @Test
    fun `detail responses embed the CDN snippet with the site key`() {
        val existing = site()
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing

        val detail = service.get(userId, existing.id)

        assertEquals(
            """<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_key"></script>""",
            detail.embedSnippet,
        )
    }

    @Test
    fun `update validates and deduplicates the new domain`() {
        val existing = site(domain = "old.example.com")
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "taken.example.com", SiteStatus.ACTIVE) } returns true

        assertThrows<InvalidDomainException> { service.update(userId, existing.id, "not a domain") }
        assertThrows<DomainAlreadyRegisteredException> { service.update(userId, existing.id, "taken.example.com") }

        // Same domain and null domain are no-ops that still return the detail.
        assertEquals("old.example.com", service.update(userId, existing.id, "old.example.com").domain)
        assertEquals("old.example.com", service.update(userId, existing.id, null).domain)
        verify(exactly = 0) { siteRepository.save(any<SiteEntity>()) }
        verify(exactly = 0) { siteRepository.saveAndFlush(any<SiteEntity>()) }
    }

    @Test
    fun `changing the domain resets verification`() {
        val existing =
            site(domain = "old.example.com")
                .copy(verifiedAt = now.minusSeconds(3600), verificationMethod = VerificationMethod.SNIPPET)
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "new.example.com", SiteStatus.ACTIVE) } returns false
        val saved = slot<SiteEntity>()
        every { siteRepository.saveAndFlush(capture(saved)) } answers { firstArg() }

        val detail = service.update(userId, existing.id, "new.example.com")

        assertEquals("new.example.com", detail.domain)
        assertEquals(null, saved.captured.verifiedAt, "domain change must reset verifiedAt")
        // Both halves must clear together or `ck_sites_verification_method_pairs` (V15) 500s the request.
        assertEquals(null, saved.captured.verificationMethod, "domain change must reset verificationMethod")
        assertEquals(now, saved.captured.updatedAt)
    }

    @Test
    fun `update maps a domain unique-index race to the same conflict error`() {
        val existing = site(domain = "old.example.com")
        every { siteRepository.findByIdAndUserId(existing.id, userId) } returns existing
        every { siteRepository.existsByUserIdAndDomainAndStatus(userId, "new.example.com", SiteStatus.ACTIVE) } returns false
        every { siteRepository.saveAndFlush(any<SiteEntity>()) } throws domainConflict()

        assertThrows<DomainAlreadyRegisteredException> { service.update(userId, existing.id, "new.example.com") }
    }

    private fun domainConflict(): DataIntegrityViolationException = integrityViolation("uq_sites_user_domain_active")

    private fun integrityViolation(constraintName: String): DataIntegrityViolationException =
        DataIntegrityViolationException(
            "dup",
            ConstraintViolationException("dup", SQLException("duplicate key"), constraintName),
        )
}
