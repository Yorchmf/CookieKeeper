package eu.cookiekeeper.scan

import eu.cookiekeeper.site.SiteRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read side of the post-install blocking verification (BACKLOG #19): turning one scan's raw probe
 * columns into the verdict a customer sees. The streak/nudge side is exercised end-to-end through
 * [ScanCompletionNotifierTest], which is where the timing rules actually bite.
 *
 * The load-bearing property here is that [BlockingVerificationService.verify] is a *pure projection of
 * one scan row*: it reads no site state, so an old scan's report page can never claim today's problem.
 */
class BlockingVerificationServiceTest {
    private val trackerClassifier = mockk<TrackerClassifier>(relaxed = true)
    private val siteRepository = mockk<SiteRepository>()
    private val now: Instant = Instant.parse("2026-08-18T09:00:00Z")
    private val service = BlockingVerificationService(trackerClassifier, siteRepository, Clock.fixed(now, ZoneOffset.UTC))

    private val siteId: UUID = UUID.randomUUID()

    @Test
    fun `an installed widget with a decidable vendor still firing is unblocked, and names it`() {
        every { trackerClassifier.describe(listOf("google-analytics.com")) } returns
            listOf(TrackerSignature(domain = "google-analytics.com", name = "Google Analytics", category = "analytics"))

        val verification =
            service.verify(
                scan(
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 0),
                    observedTrackers = "google-analytics.com",
                ),
            )

        assertEquals(BlockingStatus.UNBLOCKED, verification.status)
        assertEquals(
            listOf(BlockingVendor("google-analytics.com", "Google Analytics", "statistics")),
            verification.vendors,
            "the vendor is named with the exact category the owner must put in data-complyr-category",
        )
        assertEquals(0, verification.blockedScriptCount)
        assertTrue(verification.status.isUnresolved)
    }

    /** The crawl runs before consent, so an installed widget and no decidable vendor IS the proof. */
    @Test
    fun `an installed widget with nothing firing before consent is clean`() {
        val verification =
            service.verify(
                scan(
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 4),
                    observedTrackers = "",
                ),
            )

        assertEquals(BlockingStatus.CLEAN, verification.status)
        assertEquals(4, verification.blockedScriptCount)
        assertTrue(!verification.status.isUnresolved)
    }

    /** A snippet carrying another site's key blocks nothing here, whatever else the page looks like. */
    @Test
    fun `a site key mismatch outranks the vendor verdict`() {
        every { trackerClassifier.describe(listOf("google-analytics.com")) } returns
            listOf(TrackerSignature(domain = "google-analytics.com", name = "Google Analytics", category = "analytics"))

        val verification =
            service.verify(
                scan(
                    widget = WidgetProbe(installed = true, siteKeyMatched = false, blockedScriptCount = 0),
                    observedTrackers = "google-analytics.com",
                ),
            )

        assertEquals(BlockingStatus.WRONG_SITE_KEY, verification.status)
    }

    /** Not installed is onboarding's problem: reported, but never part of the unresolved streak. */
    @Test
    fun `a site without the widget is not installed and never counts as unresolved`() {
        val verification = service.verify(scan(widget = WidgetProbe.ABSENT))

        assertEquals(BlockingStatus.NOT_INSTALLED, verification.status)
        assertTrue(!verification.status.isUnresolved, "we do not nag about an install onboarding already asks for")
    }

    /** Scans that predate the probe have all four columns null and must claim nothing either way. */
    @Test
    fun `a scan from before the probe existed is unknown`() {
        assertEquals(BlockingVerification.UNKNOWN, service.verify(scan(widget = null)))
    }

    @Test
    fun `a scan that never completed is unknown`() {
        val verification =
            service.verify(
                scan(
                    status = ScanStatus.FAILED,
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 0),
                    observedTrackers = "google-analytics.com",
                ),
            )

        assertEquals(BlockingVerification.UNKNOWN, verification)
    }

    /** Never tell someone to gate a strictly necessary request — it would break their site. */
    @Test
    fun `a vendor the dataset calls necessary is not held against the site`() {
        every { trackerClassifier.describe(listOf("stripe.com")) } returns
            listOf(TrackerSignature(domain = "stripe.com", name = "Stripe", category = "necessary"))

        val verification =
            service.verify(
                scan(
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 0),
                    observedTrackers = "stripe.com",
                ),
            )

        assertEquals(BlockingStatus.CLEAN, verification.status)
        assertEquals(emptyList(), verification.vendors)
    }

    /** A dataset revision that drops a key must not resurrect it as a nameless row. */
    @Test
    fun `a stored key the current dataset no longer knows is dropped`() {
        every { trackerClassifier.describe(listOf("retired.example")) } returns emptyList()

        val verification =
            service.verify(
                scan(
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 0),
                    observedTrackers = "retired.example",
                ),
            )

        assertEquals(BlockingStatus.CLEAN, verification.status)
    }

    private fun scan(
        status: ScanStatus = ScanStatus.DONE,
        widget: WidgetProbe?,
        observedTrackers: String? = null,
    ) = ScanEntity(
        id = UUID.randomUUID(),
        siteId = siteId,
        status = status,
        trigger = ScanTrigger.SCHEDULED,
        widgetDetected = widget?.installed,
        widgetSiteKeyMatched = widget?.siteKeyMatched,
        blockedScriptCount = widget?.blockedScriptCount,
        observedTrackers = observedTrackers,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * The comma-joined storage format for [ScanEntity.observedTrackers]. Same trade `consent_basis_categories`
 * (V27) makes: a child table for a handful of curated dataset keys buys a join and a migration for no
 * query we will ever run.
 */
class ObservedTrackersTest {
    @Test
    fun `round-trips a list of dataset keys`() {
        val keys = listOf("doubleclick.net", "google-analytics.com")

        assertEquals(keys, ObservedTrackers.parse(ObservedTrackers.format(keys)))
    }

    @Test
    fun `an empty or null column parses to nothing`() {
        assertEquals(emptyList(), ObservedTrackers.parse(null))
        assertEquals(emptyList(), ObservedTrackers.parse(""))
    }

    /** A hostile page can name thousands of hosts; the column must not grow with them. */
    @Test
    fun `the stored set is capped`() {
        val many = (1..100).map { "vendor$it.example" }

        assertEquals(ObservedTrackers.MAX_VENDORS, ObservedTrackers.parse(ObservedTrackers.format(many)).size)
    }
}
