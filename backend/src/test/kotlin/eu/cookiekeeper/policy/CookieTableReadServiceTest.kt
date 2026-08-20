package eu.cookiekeeper.policy

import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import eu.cookiekeeper.site.VerificationMethod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The embeddable cookie table's read (ADR-27). Three things it owes the customer's own policy page:
 * the same list, wording and order as the hosted document; a table that renders whatever the page's
 * `lang` says rather than 400ing on a stray value; and no logic left for the widget to carry — every
 * display fallback is resolved here.
 *
 * The gate is the widget-config gate (active site, public key), deliberately *not* the hosted page's
 * verification gate — see [CookieTableReadService]. Both halves of that are asserted below.
 */
class CookieTableReadServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val scannedOn: LocalDate = LocalDate.of(2026, 7, 30)

    private val siteRepository = mockk<SiteRepository>()
    private val policyContextBuilder = mockk<PolicyContextBuilder>()
    private val service = CookieTableReadService(siteRepository, policyContextBuilder)

    private val siteId = UUID.randomUUID()
    private val siteKey = "pk_test"

    private fun site(verifiedAt: Instant? = now): SiteEntity =
        SiteEntity(
            id = siteId,
            userId = UUID.randomUUID(),
            domain = "acme.example.com",
            siteKey = siteKey,
            status = SiteStatus.ACTIVE,
            verifiedAt = verifiedAt,
            verificationMethod = verifiedAt?.let { VerificationMethod.SNIPPET },
            createdAt = now,
            updatedAt = now,
        )

    private fun cookie(
        name: String,
        provider: String? = null,
        expiry: String? = null,
        domain: String? = null,
    ) = PolicyCookie(name = name, provider = provider, expiry = expiry, domain = domain)

    private fun stubSite(found: Boolean = true) {
        every { siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE) } returns if (found) site() else null
    }

    private fun stubCookies(
        byCategory: Map<String, List<PolicyCookie>> = emptyMap(),
        unclassified: List<PolicyCookie> = emptyList(),
        scanned: LocalDate? = scannedOn,
    ) {
        every { policyContextBuilder.cookies(siteId) } returns
            PolicyCookies(byCategory = byCategory, unclassified = unclassified, scannedOn = scanned)
    }

    @Test
    fun `sections follow the canonical category order with the unclassified bucket last`() {
        stubSite()
        // Deliberately supplied out of order — the response order must come from the taxonomy, not the map.
        stubCookies(
            byCategory =
                mapOf(
                    "marketing" to listOf(cookie("_fbp")),
                    "necessary" to listOf(cookie("PHPSESSID")),
                    "statistics" to listOf(cookie("_ga")),
                ),
            unclassified = listOf(cookie("mystery")),
        )

        val response = service.read(siteKey, "en")

        assertEquals(
            listOf(
                "Strictly necessary cookies",
                "Statistics cookies",
                "Marketing cookies",
                "Other cookies",
            ),
            response.sections.map { it.heading },
        )
        assertEquals("2026-07-30", response.scannedOn)
    }

    @Test
    fun `an empty category is skipped rather than emitted as an empty table`() {
        stubSite()
        stubCookies(byCategory = mapOf("necessary" to listOf(cookie("PHPSESSID")), "marketing" to emptyList()))

        val response = service.read(siteKey, "en")

        assertEquals(1, response.sections.size)
        assertEquals("Strictly necessary cookies", response.sections.single().heading)
    }

    @Test
    fun `a site that has never completed a scan reports no sections and no scan date`() {
        stubSite()
        stubCookies(scanned = null)

        val response = service.read(siteKey, "en")

        assertTrue(response.sections.isEmpty())
        assertNull(response.scannedOn)
        // The widget renders labels.noCookies instead of a table, so the sentence must always be there.
        assertTrue(response.labels.noCookies.isNotBlank())
    }

    @Test
    fun `provider falls back to the cookie's domain, then to the unknown marker`() {
        stubSite()
        stubCookies(
            byCategory =
                mapOf(
                    "statistics" to
                        listOf(
                            cookie("_ga", provider = "Google Analytics", domain = "google.com"),
                            cookie("_hj", provider = "  ", domain = "hotjar.com"),
                            cookie("mystery", provider = null, domain = null),
                        ),
                ),
        )

        val cookies =
            service
                .read(siteKey, "en")
                .sections
                .single()
                .cookies

        assertEquals(listOf("Google Analytics", "hotjar.com", "—"), cookies.map { it.provider })
    }

    @Test
    fun `a cookie with no expiry is reported as a session cookie`() {
        stubSite()
        stubCookies(byCategory = mapOf("necessary" to listOf(cookie("PHPSESSID"), cookie("_ck", expiry = "365 days"))))

        val cookies =
            service
                .read(siteKey, "en")
                .sections
                .single()
                .cookies

        assertEquals(listOf("Session", "365 days"), cookies.map { it.expiry })
    }

    @Test
    fun `a region tag resolves to its base language and the labels come back translated`() {
        stubSite()
        stubCookies(byCategory = mapOf("necessary" to listOf(cookie("PHPSESSID"))))

        val response = service.read(siteKey, "de-AT")
        val german = PolicyStrings.forLanguage("de")

        assertEquals("de", response.language)
        assertEquals(german.colProvider, response.labels.provider)
        assertEquals(german.category("necessary").name, response.sections.single().heading)
    }

    @Test
    fun `an unsupported or absent language falls back to the default instead of failing`() {
        stubSite()
        stubCookies()

        // This renders inside someone else's page: a stray lang attribute must never cost them the table.
        assertEquals(PolicyLanguages.DEFAULT, service.read(siteKey, "xx").language)
        assertEquals(PolicyLanguages.DEFAULT, service.read(siteKey, null).language)
    }

    @Test
    fun `an unknown or inactive site key is a generic not-found`() {
        stubSite(found = false)

        assertThrows<PolicyNotFoundException> { service.read(siteKey, "en") }
        // The refusal answers before any cookie is read, so it can't depend on scan state.
        verify(exactly = 0) { policyContextBuilder.cookies(any()) }
    }

    @Test
    fun `an unverified site still serves its table, unlike the hosted page`() {
        // ADR-17 gates /p/{publicId} because *we* publish a claim about a domain we don't control. Here
        // the customer's own server publishes it, so the verification gate would only break the feature
        // for the sites that need it most.
        every { siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE) } returns site(verifiedAt = null)
        stubCookies(byCategory = mapOf("necessary" to listOf(cookie("PHPSESSID"))))

        assertEquals(1, service.read(siteKey, "en").sections.size)
    }
}
