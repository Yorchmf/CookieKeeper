package com.complyr.scan

import com.complyr.common.ComplyrProperties
import com.microsoft.playwright.options.Cookie
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anonymous crawl adapter around the browser-free [ScanEngine] seam. Unlike the authenticated
 * [PlaywrightScanCrawlerTest], there are deliberately NO ownership/verified gates to exercise (the
 * validator inside the engine is the sole guard). This covers what the adapter itself owns: driving
 * the engine in QUICK mode over the claim's bare domain, and classifying then projecting the observed
 * cookies onto `public_scan_cookies` rows.
 */
class PlaywrightPublicScanCrawlerTest {
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

    private val cookieWriter = mockk<PublicScanCookieWriter>(relaxed = true)
    private val classifier = mockk<CookieClassifier>()

    private val publicScanId: UUID = UUID.randomUUID()
    private val claim =
        ClaimedPublicScan(
            jobId = UUID.randomUUID(),
            publicScanId = publicScanId,
            domain = "acme.example",
            attempt = 1,
            maxAttempts = 1,
        )

    /** Records what the crawler asked the engine to crawl, so we can assert domain + mode propagation. */
    private class FakeScanEngine(
        private val result: EngineCrawlResult,
    ) : ScanEngine {
        var callCount = 0
        var lastDomain: String? = null
        var lastMode: CrawlMode? = null

        override fun crawl(
            domain: String,
            mode: CrawlMode,
        ): EngineCrawlResult {
            callCount++
            lastDomain = domain
            lastMode = mode
            return result
        }
    }

    private fun crawlerWith(engine: FakeScanEngine): PublicScanCrawler =
        PlaywrightPublicScanCrawler(cookieWriter, classifier, engine, properties)

    @Test
    fun `crawls the claim's domain in QUICK mode and propagates the page count`() {
        every { classifier.classify(any()) } answers { firstArg() }
        val engine = FakeScanEngine(EngineCrawlResult(pagesCrawled = 1, cookies = emptyList()))

        val result = crawlerWith(engine).crawl(claim)

        assertEquals(1, result.pagesCrawled, "the engine's page count flows through to the scan result")
        assertEquals("acme.example", engine.lastDomain, "the crawler uses the claim's visitor-supplied domain")
        assertEquals(CrawlMode.QUICK, engine.lastMode, "the anonymous funnel is a single-page quick crawl")
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `classifies observed cookies and persists them projected onto public_scan_cookies rows`() {
        // Classifier stands in as an enricher: flip the one cookie to a known statistics cookie so we can
        // assert the classified fields survive the projection to the public entity.
        every { classifier.classify(any()) } answers {
            firstArg<List<ScanCookieEntity>>().map {
                it.copy(category = "statistics", provider = "Google Analytics", isKnown = true)
            }
        }
        val cookies = listOf(Cookie("_ga", "GA1.2").setDomain(".acme.example"))
        val engine = FakeScanEngine(EngineCrawlResult(pagesCrawled = 1, cookies = cookies))
        val persisted = slot<List<PublicScanCookieEntity>>()

        crawlerWith(engine).crawl(claim)

        verify(exactly = 1) { classifier.classify(any()) }
        verify(exactly = 1) { cookieWriter.replace(publicScanId, capture(persisted)) }
        val row = persisted.captured.single()
        assertEquals(publicScanId, row.publicScanId, "rows are FK'd to this public scan")
        assertEquals("_ga", row.name)
        assertEquals("statistics", row.category, "the classifier's category survives the projection")
        assertEquals("Google Analytics", row.provider)
        assertTrue(row.isKnown)
    }
}
