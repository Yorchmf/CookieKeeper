package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.analytics.dto.OverviewActionKind
import com.complyr.banner.ConsentCategory
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import com.complyr.site.VerificationMethod
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [OverviewService] — the account roll-up behind the dashboard home. Repositories are
 * mocked: what is under test is the fan-in (which site gets which action, in what order) and the headline
 * arithmetic, not the SQL, which [AnalyticsApiIntegrationTest]'s sibling integration coverage exercises.
 */
class OverviewServiceTest {
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-12T12:00:00Z")
    private val range = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)

    private val siteRepository = mockk<SiteRepository>()
    private val overviewRepository = mockk<OverviewRepository>()
    private val consentRepository = mockk<ConsentAnalyticsRepository>()
    private val rangeResolver =
        mockk<AnalyticsRangeResolver> {
            every { resolve(userId, any()) } returns range
        }

    private val service = OverviewService(siteRepository, overviewRepository, consentRepository, rangeResolver)

    private fun site(
        domain: String,
        verified: Boolean = true,
    ) = SiteEntity(
        userId = userId,
        domain = domain,
        siteKey = "key-$domain",
        verifiedAt = if (verified) now.minusSeconds(86_400) else null,
        verificationMethod = if (verified) VerificationMethod.SNIPPET else null,
    )

    @Test
    fun `account with no sites returns an empty overview without querying anything else`() {
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns emptyList()

        val result = service.overview(userId, AnalyticsFilter())

        assertEquals(range, result.range)
        assertEquals(0, result.headline.activeSites)
        assertEquals(0L, result.headline.consentEvents)
        assertNull(result.headline.acceptAllRate)
        assertNull(result.headline.lastScanAt)
        assertTrue(result.actions.isEmpty())
        // An account with no sites has completed no onboarding step — the checklist shows every one to do.
        assertEquals(false, result.onboarding.addedSite)
        assertEquals(false, result.onboarding.scanned)
        assertEquals(false, result.onboarding.customisedBanner)
        assertEquals(false, result.onboarding.verified)
        // The batch queries take `site_id IN (...)`, which is invalid SQL for an empty set — the service
        // must return before reaching them, not merely produce an empty result.
        verify(exactly = 0) { overviewRepository.latestCompletedScans(any()) }
        verify(exactly = 0) { overviewRepository.maxBannerVersions(any()) }
        verify(exactly = 0) { consentRepository.accountActionCounts(any(), any(), any()) }
    }

    @Test
    fun `onboarding reports a step done when any active site has reached it`() {
        val verifiedUnscanned = site("verified.com")
        val scannedUnverified = site("scanned.com", verified = false)
        val scanId = UUID.randomUUID()
        stub(
            sites = listOf(verifiedUnscanned, scannedUnverified),
            scans = listOf(LatestScanRow(scannedUnverified.id, scanId, now.minusSeconds(3_600))),
            // Only the scanned site has an edited banner (v2); the other still carries the seeded v1.
            bannerVersions =
                listOf(
                    BannerVersionRow(verifiedUnscanned.id, version = 1),
                    BannerVersionRow(scannedUnverified.id, version = 2),
                ),
        )

        val onboarding = service.overview(userId, AnalyticsFilter()).onboarding

        assertTrue(onboarding.addedSite)
        // Neither site is both scanned and verified, but each step is satisfied by *some* site.
        assertTrue(onboarding.scanned)
        assertTrue(onboarding.customisedBanner)
        assertTrue(onboarding.verified)
    }

    @Test
    fun `a site still on the seeded v1 banner does not count as customised`() {
        val site = site("example.com", verified = false)
        stub(
            sites = listOf(site),
            // v1 is the default seeded on creation — no customer edit yet.
            bannerVersions = listOf(BannerVersionRow(site.id, version = 1)),
        )

        val onboarding = service.overview(userId, AnalyticsFilter()).onboarding

        assertTrue(onboarding.addedSite)
        assertEquals(false, onboarding.scanned)
        assertEquals(false, onboarding.customisedBanner)
        assertEquals(false, onboarding.verified)
    }

    @Test
    fun `headline sums consent decisions and derives the accept-all rate`() {
        val site = site("example.com")
        val scanId = UUID.randomUUID()
        stub(
            sites = listOf(site),
            scans = listOf(LatestScanRow(site.id, scanId, now.minusSeconds(3_600))),
            cookies = listOf(ScanCookieTotals(scanId, total = 12, insecure = 0)),
            policies = listOf(LatestPolicyRow(site.id, version = 2, publishedAt = now)),
            consent =
                listOf(
                    ActionCountRow(AnalyticsService.ACTION_ACCEPT_ALL, 30),
                    ActionCountRow(AnalyticsService.ACTION_REJECT_ALL, 50),
                    ActionCountRow(AnalyticsService.ACTION_CUSTOM, 20),
                ),
        )

        val headline = service.overview(userId, AnalyticsFilter()).headline

        assertEquals(1, headline.activeSites)
        assertEquals(100L, headline.consentEvents)
        // Custom consents count as decisions but NOT as acceptances: 30/100, not 50/100.
        assertEquals(0.30, headline.acceptAllRate)
        assertEquals(12, headline.cookiesFound)
        assertEquals(now.minusSeconds(3_600), headline.lastScanAt)
    }

    @Test
    fun `accept-all rate is null rather than zero when no decisions were recorded`() {
        val site = site("example.com")
        stub(sites = listOf(site), consent = emptyList())

        assertNull(service.overview(userId, AnalyticsFilter()).headline.acceptAllRate)
    }

    @Test
    fun `each site reports only its most severe action, ordered by severity`() {
        val unverified = site("unverified.com", verified = false)
        val unscanned = site("unscanned.com")
        val noPolicy = site("no-policy.com")
        val stale = site("stale.com")
        val insecure = site("insecure.com")
        val healthy = site("healthy.com")
        val scanned = now.minusSeconds(3_600)
        val scanIds = listOf(noPolicy, stale, insecure, healthy).associateWith { UUID.randomUUID() }

        stub(
            // Deliberately NOT in severity order — the service must sort, not rely on the site order.
            sites = listOf(healthy, insecure, stale, noPolicy, unscanned, unverified),
            scans = scanIds.map { (site, scanId) -> LatestScanRow(site.id, scanId, scanned) },
            cookies =
                listOf(
                    ScanCookieTotals(scanIds.getValue(noPolicy), total = 5, insecure = 0),
                    ScanCookieTotals(scanIds.getValue(stale), total = 5, insecure = 0),
                    ScanCookieTotals(scanIds.getValue(insecure), total = 5, insecure = 3),
                    ScanCookieTotals(scanIds.getValue(healthy), total = 5, insecure = 0),
                ),
            policies =
                listOf(
                    // Published BEFORE the scan → cannot describe what the scan found → stale.
                    LatestPolicyRow(stale.id, version = 1, publishedAt = scanned.minusSeconds(60)),
                    LatestPolicyRow(insecure.id, version = 1, publishedAt = scanned.plusSeconds(60)),
                    LatestPolicyRow(healthy.id, version = 1, publishedAt = scanned.plusSeconds(60)),
                ),
        )

        val actions = service.overview(userId, AnalyticsFilter()).actions

        assertEquals(
            listOf("unverified", "never_scanned", "policy_missing", "policy_stale", "insecure_cookies"),
            actions.map { it.kind },
        )
        assertEquals(
            listOf("unverified.com", "unscanned.com", "no-policy.com", "stale.com", "insecure.com"),
            actions.map { it.domain },
        )
        // A site with nothing wrong contributes no row at all.
        assertTrue(actions.none { it.domain == "healthy.com" })
        // Only the magnitude-bearing kind carries a count.
        assertEquals(3, actions.single { it.kind == "insecure_cookies" }.count)
        assertTrue(actions.filter { it.kind != "insecure_cookies" }.all { it.count == null })
    }

    @Test
    fun `wire kinds round-trip to the enum so severity ordering cannot silently drift`() {
        val kinds = OverviewActionKind.entries.map { it.name.lowercase() }
        assertEquals(kinds, kinds.map { OverviewActionKind.valueOf(it.uppercase()).name.lowercase() })
    }

    private fun stub(
        sites: List<SiteEntity>,
        scans: List<LatestScanRow> = emptyList(),
        cookies: List<ScanCookieTotals> = emptyList(),
        policies: List<LatestPolicyRow> = emptyList(),
        consent: List<ActionCountRow> = emptyList(),
        bannerVersions: List<BannerVersionRow> = emptyList(),
    ) {
        every { siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns sites
        every { overviewRepository.latestCompletedScans(any()) } returns scans
        every { overviewRepository.cookieTotals(any(), ConsentCategory.NECESSARY.key) } returns cookies
        every { overviewRepository.latestPublishedPolicies(any()) } returns policies
        every { overviewRepository.maxBannerVersions(any()) } returns bannerVersions
        every { consentRepository.accountActionCounts(any(), range.from, range.to) } returns consent
    }
}
