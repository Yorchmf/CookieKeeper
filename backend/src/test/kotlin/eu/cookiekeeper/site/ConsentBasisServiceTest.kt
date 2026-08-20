package eu.cookiekeeper.site

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The consent basis (BACKLOG #18) — when a site's tracking changes materially enough that consents
 * collected earlier no longer cover it, and visitors have to be asked again.
 *
 * The cases that matter are the ones that decide whether real visitors get re-prompted: the very first
 * observation (must NOT re-prompt anyone), a genuinely new category (must), a category disappearing and
 * returning (must not, twice), and two scans racing (exactly one bump).
 */
class ConsentBasisServiceTest {
    private val siteRepository = mockk<SiteRepository>(relaxed = true)
    private val now: Instant = Instant.parse("2026-08-20T09:00:00Z")
    private val service = ConsentBasisService(siteRepository, Clock.fixed(now, ZoneOffset.UTC))

    private val siteId: UUID = UUID.randomUUID()

    @Test
    fun `the first observation seeds the basis without bumping the version`() {
        // Deploying this feature must not re-prompt every visitor on the internet: a site whose basis
        // was never recorded starts from what it is already doing.
        givenSite(storedCategories = null, version = 1)

        val added = service.record(siteId, setOf("statistics", "marketing"))

        assertTrue(added.isEmpty(), "seeding is not a change")
        verify(exactly = 1) { siteRepository.seedConsentBasis(siteId, "marketing,statistics") }
        verify(exactly = 0) { siteRepository.bumpConsentBasis(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a category newly in use bumps the version and reports what was added`() {
        givenSite(storedCategories = "statistics", version = 3)
        every { siteRepository.bumpConsentBasis(siteId, any(), any(), any(), any()) } returns 1

        val added = service.record(siteId, setOf("statistics", "marketing"))

        assertEquals(setOf("marketing"), added)
        verify {
            siteRepository.bumpConsentBasis(
                siteId = siteId,
                categories = "marketing,statistics",
                added = "marketing",
                changedAt = now,
                expectedCategories = "statistics",
            )
        }
    }

    @Test
    fun `an observation the basis already covers changes nothing`() {
        givenSite(storedCategories = "marketing,statistics", version = 2)

        assertTrue(service.record(siteId, setOf("statistics")).isEmpty())
        verify(exactly = 0) { siteRepository.bumpConsentBasis(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a category that disappears and returns does not re-prompt a second time`() {
        // The stored set is a union, never a replacement — otherwise a flaky third-party would cost the
        // customer a re-prompt wave on every scan that missed it.
        givenSite(storedCategories = "marketing,statistics", version = 2)

        assertTrue(service.record(siteId, setOf("statistics")).isEmpty(), "marketing gone: not a change")
        assertTrue(service.record(siteId, setOf("statistics", "marketing")).isEmpty(), "marketing back: still covered")
        verify(exactly = 0) { siteRepository.bumpConsentBasis(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a concurrent bump wins and this observation is dropped`() {
        // Compare-and-set: another scan for the same site wrote between our read and our write. Its
        // version stands, and ours is recovered on the next completed scan because we compare against
        // the STORED basis rather than the previous scan.
        givenSite(storedCategories = "statistics", version = 3)
        every { siteRepository.bumpConsentBasis(siteId, any(), any(), any(), any()) } returns 0

        assertTrue(service.record(siteId, setOf("statistics", "marketing")).isEmpty())
    }

    @Test
    fun `a blank category is not a basis`() {
        givenSite(storedCategories = "statistics", version = 1)

        assertTrue(service.record(siteId, setOf("statistics", "", "  ")).isEmpty())
        verify(exactly = 0) { siteRepository.bumpConsentBasis(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a site that no longer exists is not an error`() {
        // The scan listener runs after the scan's own transaction commits; the site may have been
        // deleted in between, and that must not surface as a failure.
        every { siteRepository.findById(siteId) } returns Optional.empty()

        assertTrue(service.record(siteId, setOf("marketing")).isEmpty())
    }

    @Test
    fun `a site recorded as using nothing decidable is still a recorded basis`() {
        // An empty stored value means "recorded, nothing in use" — distinct from null, which means
        // "never recorded". The first tracker on such a site is a real change.
        givenSite(storedCategories = "", version = 1)
        every { siteRepository.bumpConsentBasis(siteId, any(), any(), any(), any()) } returns 1

        assertEquals(setOf("marketing"), service.record(siteId, setOf("marketing")))
        verify { siteRepository.bumpConsentBasis(siteId, "marketing", "marketing", now, "") }
    }

    private fun givenSite(
        storedCategories: String?,
        version: Int,
    ) {
        every { siteRepository.findById(siteId) } returns
            Optional.of(
                SiteEntity(
                    id = siteId,
                    userId = UUID.randomUUID(),
                    domain = "example.eu",
                    siteKey = "pk_test",
                    consentBasisVersion = version,
                    consentBasisCategories = storedCategories,
                ),
            )
    }
}
