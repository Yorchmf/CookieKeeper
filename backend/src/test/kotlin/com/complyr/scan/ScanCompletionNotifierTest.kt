package com.complyr.scan

import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.notify.BestEffortEmailDelivery
import com.complyr.notify.ComposedEmail
import com.complyr.notify.EmailSender
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The send/skip decision for scan-complete emails. This is the class that decides how often we are
 * allowed into a customer's inbox, so the cases that matter are the negatives: a manual re-scan the
 * user is already watching, an unchanged nightly re-scan, and an archived site the customer retired.
 * Every one of those, wrong, is a customer who filters us out.
 */
class ScanCompletionNotifierTest {
    private val composer = mockk<ScanEmailComposer>()
    private val sender = mockk<EmailSender>()
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val siteRepository = mockk<SiteRepository>()
    private val userRepository = mockk<UserRepository>()

    private val notifier =
        ScanCompletionNotifier(
            composer,
            BestEffortEmailDelivery(sender),
            scanRepository,
            scanCookieRepository,
            siteRepository,
            userRepository,
        )

    private val siteId: UUID = UUID.randomUUID()
    private val userId: UUID = UUID.randomUUID()
    private val scanId: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-08-07T04:20:00Z")

    @BeforeEach
    fun stubHappyPath() {
        every { siteRepository.findById(siteId) } returns Optional.of(site())
        every { userRepository.findById(userId) } returns Optional.of(user())
        every { composer.scanCompletedEmail(any(), any()) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs
    }

    // ---- what gets an email ------------------------------------------------------------------

    @Test
    fun `the first scan after a site is added always mails`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga"), trackerCount = 1)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    @Test
    fun `a manual re-scan never mails`() {
        // No repository stubs at all: a manual trigger must short-circuit before touching the DB,
        // so any lookup here would fail the test with a missing-stub error.
        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.MANUAL)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `a scheduled re-scan that found a new cookie mails`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga", "_fbp"), trackerCount = 2)
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 2)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    @Test
    fun `a scheduled re-scan that found a new tracker mails even when the cookies are identical`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga"), trackerCount = 4)
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    /** The load-bearing negative: nightly re-scans of a stable site must be completely silent. */
    @Test
    fun `a scheduled re-scan that found nothing new stays silent`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga", "_fbp"), trackerCount = 2)
        givenPreviousScan(cookies = listOf("_fbp", "_ga"), trackerCount = 2)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    /**
     * A cookie disappearing changes the name set and so also mails. Intended: a tracker going away is
     * a policy-affecting change the customer's published cookie policy needs to reflect.
     */
    @Test
    fun `a scheduled re-scan where a cookie disappeared mails`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga"), trackerCount = 1)
        givenPreviousScan(cookies = listOf("_ga", "_fbp"), trackerCount = 1)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    @Test
    fun `a scheduled re-scan with no earlier completed scan to compare against mails`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga"), trackerCount = 1)
        every {
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                siteId,
                ScanStatus.DONE,
                any(),
            )
        } returns null

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    // ---- who gets it -------------------------------------------------------------------------

    @Test
    fun `an archived site is never mailed about`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns Optional.of(site(status = SiteStatus.ARCHIVED))

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `a site deleted between the crawl and the send is skipped, not thrown`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns Optional.empty()

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `an owner deleted between the crawl and the send is skipped, not thrown`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga"), trackerCount = 1)
        every { userRepository.findById(userId) } returns Optional.empty()

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    // ---- what the email says -----------------------------------------------------------------

    @Test
    fun `composes in the owner's locale with the observed counts`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga", "_fbp", "sid"), trackerCount = 2)
        val locale = slot<String>()
        val summary = slot<ScanSummary>()
        every { composer.scanCompletedEmail(capture(locale), capture(summary)) } returns
            ComposedEmail("subject", "body")

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        assertEquals("de", locale.captured, "the email must render in the owner's locale")
        assertEquals(3, summary.captured.cookieCount, "cookie count comes from this scan's rows")
        assertEquals(2, summary.captured.marketingTrackerCount)
        assertEquals("shop.example.com", summary.captured.domain)
        assertEquals(scanId, summary.captured.scanId)
        assertEquals(siteId, summary.captured.siteId)
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun givenScan(
        trigger: ScanTrigger,
        cookies: List<String>,
        trackerCount: Int,
    ) {
        every { scanRepository.findById(scanId) } returns
            Optional.of(scan(scanId, trigger, trackerCount, createdAt = now))
        every { scanCookieRepository.findByScanId(scanId) } returns cookies.map(::cookie)
    }

    private fun givenPreviousScan(
        cookies: List<String>,
        trackerCount: Int,
    ) {
        val previousId = UUID.randomUUID()
        every {
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                siteId,
                ScanStatus.DONE,
                now,
            )
        } returns scan(previousId, ScanTrigger.SCHEDULED, trackerCount, createdAt = now.minus(Duration.ofDays(7)))
        every { scanCookieRepository.findByScanId(previousId) } returns cookies.map(::cookie)
    }

    private fun scan(
        id: UUID,
        trigger: ScanTrigger,
        trackerCount: Int,
        createdAt: Instant,
    ) = ScanEntity(
        id = id,
        siteId = siteId,
        status = ScanStatus.DONE,
        trigger = trigger,
        marketingTrackerCount = trackerCount,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun cookie(name: String) = ScanCookieEntity(scanId = scanId, name = name)

    private fun site(status: SiteStatus = SiteStatus.ACTIVE) =
        SiteEntity(
            id = siteId,
            userId = userId,
            domain = "shop.example.com",
            siteKey = "pk_test",
            status = status,
        )

    private fun user() =
        UserEntity(
            id = userId,
            email = "owner@example.com",
            passwordHash = "hash",
            locale = "de",
            createdAt = now.minus(Duration.ofDays(30)),
            verifiedAt = now.minus(Duration.ofDays(30)),
        )
}
