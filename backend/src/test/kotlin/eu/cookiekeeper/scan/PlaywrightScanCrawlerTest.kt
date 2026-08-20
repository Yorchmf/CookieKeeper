package eu.cookiekeeper.scan

import com.microsoft.playwright.options.Cookie
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Orchestration around the browser-free [ScanEngine] seam: the ownership/state gates and the
 * classify-then-persist path, exercised with a [FakeScanEngine] so no Chromium is launched. The crawl
 * itself is the engine's concern and is not re-tested here.
 */
class PlaywrightScanCrawlerTest {
    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "support@cookiekeeper.eu",
        )

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val cookieWriter = mockk<ScanCookieWriter>(relaxed = true)
    private val classifier = mockk<CookieClassifier>()
    private val trackerClassifier = mockk<TrackerClassifier>()

    private val siteId: UUID = UUID.randomUUID()
    private val claim =
        ClaimedScan(
            jobId = UUID.randomUUID(),
            scanId = UUID.randomUUID(),
            siteId = siteId,
            attempt = 1,
            maxAttempts = 3,
        )

    /** Records what the crawler asked the engine to crawl, so we can assert domain + mode propagation. */
    private class FakeScanEngine(
        private val result: EngineCrawlResult,
    ) : ScanEngine {
        var callCount = 0
        var lastDomain: String? = null
        var lastMode: CrawlMode? = null
        var lastExpectedSiteKey: String? = null

        override fun crawl(
            domain: String,
            mode: CrawlMode,
            expectedSiteKey: String?,
        ): EngineCrawlResult {
            callCount++
            lastDomain = domain
            lastMode = mode
            lastExpectedSiteKey = expectedSiteKey
            return result
        }
    }

    private fun site(
        status: SiteStatus = SiteStatus.ACTIVE,
        verifiedAt: Instant? = now,
    ): SiteEntity =
        SiteEntity(
            id = siteId,
            userId = UUID.randomUUID(),
            domain = "example.com",
            siteKey = "pk_key",
            status = status,
            verifiedAt = verifiedAt,
        )

    private fun crawlerWith(engine: FakeScanEngine): PlaywrightScanCrawler =
        PlaywrightScanCrawler(siteRepository, cookieWriter, classifier, trackerClassifier, engine, properties)

    @Test
    fun `unknown site fails as an internal error and never crawls`() {
        every { siteRepository.findById(siteId) } returns Optional.empty()
        val engine = FakeScanEngine(EngineCrawlResult(pagesCrawled = 0, cookies = emptyList(), thirdPartyHosts = emptySet()))

        val ex = assertThrows<ScanTargetException> { crawlerWith(engine).crawl(claim) }

        assertEquals(ScanFailureReason.INTERNAL, ex.reason)
        assertEquals(0, engine.callCount, "the engine must not launch a browser for a missing site")
    }

    @Test
    fun `archived site fails as an internal error and never crawls`() {
        every { siteRepository.findById(siteId) } returns Optional.of(site(status = SiteStatus.ARCHIVED))
        val engine = FakeScanEngine(EngineCrawlResult(pagesCrawled = 0, cookies = emptyList(), thirdPartyHosts = emptySet()))

        val ex = assertThrows<ScanTargetException> { crawlerWith(engine).crawl(claim) }

        assertEquals(ScanFailureReason.INTERNAL, ex.reason)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `unverified site still crawls, in QUICK mode (ADR-17 - verification buys depth, not permission)`() {
        every { siteRepository.findById(siteId) } returns Optional.of(site(verifiedAt = null))
        every { classifier.classify(any()) } answers { firstArg() }
        every { trackerClassifier.countMarketingTrackers(any()) } returns 0
        every { trackerClassifier.identifyDecidable(any()) } returns emptyList()
        val engine = FakeScanEngine(EngineCrawlResult(pagesCrawled = 1, cookies = emptyList(), thirdPartyHosts = emptySet()))

        val result = crawlerWith(engine).crawl(claim)

        assertEquals(1, engine.callCount, "an unverified site must still be scanned — it used to dead-end here")
        assertEquals(CrawlMode.QUICK, engine.lastMode, "unverified gets the same single-page pass as the anonymous funnel")
        assertEquals("example.com", engine.lastDomain)
        assertEquals(1, result.pagesCrawled)
    }

    @Test
    fun `verified site crawls its own domain in FULL mode, classifies, and propagates the counts`() {
        every { siteRepository.findById(siteId) } returns Optional.of(site())
        // Classifier is identity here — we assert wiring, not signature matching (covered elsewhere).
        every { classifier.classify(any()) } answers { firstArg() }
        val hosts = setOf("ad.doubleclick.net", "www.google-analytics.com")
        // One classifier pass yields both numbers: the marketing count the report shows and the full
        // consent-decidable set the blocking verification names (BACKLOG #19).
        every { trackerClassifier.identifyDecidable(hosts) } returns
            listOf(
                TrackerSignature(domain = "doubleclick.net", name = "Google Ads", category = "marketing"),
                TrackerSignature(domain = "google-analytics.com", name = "Google Analytics", category = "analytics"),
            )
        val cookies = listOf(Cookie("_ga", "GA1.2").setDomain(".example.com"))
        val engine =
            FakeScanEngine(
                EngineCrawlResult(
                    pagesCrawled = 3,
                    cookies = cookies,
                    thirdPartyHosts = hosts,
                    widget = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 1),
                ),
            )

        val result = crawlerWith(engine).crawl(claim)

        assertEquals(3, result.pagesCrawled, "the engine's page count flows through to the scan result")
        assertEquals(1, result.marketingTrackerCount, "only the marketing rows count as marketing trackers")
        assertEquals("example.com", engine.lastDomain, "the crawler must use the site's own domain")
        assertEquals(CrawlMode.FULL, engine.lastMode, "the authenticated path is a full multi-page crawl")
        assertEquals("pk_key", engine.lastExpectedSiteKey, "the in-page probe compares against this site's own key")
        assertEquals(
            listOf("doubleclick.net", "google-analytics.com"),
            result.observedTrackers,
            "every consent-decidable vendor is carried as a dataset key, never as the observed host (§4)",
        )
        assertEquals(WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 1), result.widget)
        verify(exactly = 1) { classifier.classify(any()) }
        verify(exactly = 1) { trackerClassifier.identifyDecidable(hosts) }
        verify(exactly = 1) { cookieWriter.replace(claim.scanId, any()) }
    }
}
