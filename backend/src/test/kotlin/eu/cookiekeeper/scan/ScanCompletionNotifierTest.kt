package eu.cookiekeeper.scan

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.notify.BestEffortEmailDelivery
import eu.cookiekeeper.notify.ComposedEmail
import eu.cookiekeeper.notify.EmailSender
import eu.cookiekeeper.notify.NotificationPreferenceService
import eu.cookiekeeper.notify.NotificationPreferences
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
    private val notificationPreferences = mockk<NotificationPreferenceService>()
    private val trackerClassifier = mockk<TrackerClassifier>()

    private val siteId: UUID = UUID.randomUUID()
    private val userId: UUID = UUID.randomUUID()
    private val scanId: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-08-07T04:20:00Z")

    // A real calculator and a real blocking service over the same mocked repositories: the send gate,
    // the dashboard and the nudge clock all share these exact types, so "changed since last scan" and
    // "still not blocking after N days" are exercised end-to-end here rather than stubbed away.
    private val notifier =
        ScanCompletionNotifier(
            composer,
            BestEffortEmailDelivery(sender),
            ScanEmailTargetResolver(scanRepository, siteRepository, userRepository),
            scanCookieRepository,
            ScanDiffCalculator(scanRepository, scanCookieRepository),
            BlockingVerificationService(trackerClassifier, siteRepository, Clock.fixed(now, ZoneOffset.UTC)),
            notificationPreferences,
        )

    @BeforeEach
    fun stubHappyPath() {
        every { siteRepository.findById(siteId) } returns Optional.of(site())
        every { userRepository.findById(userId) } returns Optional.of(user())
        every { composer.scanCompletedEmail(any(), any()) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs
        // Default: the owner has not opted out of anything (the all-on default of an untouched account).
        every { notificationPreferences.get(userId) } returns NotificationPreferences.DEFAULT
        // Streak bookkeeping runs on every completed scan; default to "nothing to change".
        every { trackerClassifier.describe(any()) } returns emptyList()
        every { siteRepository.clearBlockingAlert(siteId) } returns 0
        every { siteRepository.startBlockingAlert(siteId, any()) } returns 1
        every { siteRepository.markBlockingAlertNotified(siteId, any(), any()) } returns 1
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
        // The scan is still read — streak bookkeeping (BACKLOG #19) runs for every completed scan —
        // but nothing may reach the inbox: the user is watching the row they just clicked.
        givenScan(ScanTrigger.MANUAL, cookies = listOf("_ga"), trackerCount = 1)

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

    // ---- opt-out -----------------------------------------------------------------------------

    @Test
    fun `an owner who turned off first-scan emails is not mailed about a site-added scan`() {
        givenScan(ScanTrigger.SITE_ADDED, cookies = listOf("_ga"), trackerCount = 1)
        every { notificationPreferences.get(userId) } returns
            NotificationPreferences(scanComplete = false, scanChanges = true)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SITE_ADDED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `an owner who turned off change alerts is not mailed about a scheduled re-scan with new findings`() {
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga", "_fbp"), trackerCount = 2)
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 2)
        every { notificationPreferences.get(userId) } returns
            NotificationPreferences(scanComplete = true, scanChanges = false)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `the first-scan and change toggles are independent`() {
        // scanComplete off must not silence a scheduled change alert.
        givenScan(ScanTrigger.SCHEDULED, cookies = listOf("_ga", "_fbp"), trackerCount = 2)
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 2)
        every { notificationPreferences.get(userId) } returns
            NotificationPreferences(scanComplete = false, scanChanges = true)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "subject", "body") }
    }

    // ---- the post-install blocking nudge (BACKLOG #19) ----------------------------------------

    /**
     * The point of the whole feature: an unchanged nightly re-scan is normally silent, but a site that
     * has been *stably* non-compliant for weeks is exactly what that silence hides.
     */
    @Test
    fun `an unchanged re-scan still nudges when the widget has not been blocking since before the grace period`() {
        givenUnblockedScan()
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(site(blockingAlertSince = now.minus(Duration.ofDays(9))))
        val summary = slot<BlockingAlertSummary>()
        every { composer.blockingAlertEmail(any(), capture(summary)) } returns ComposedEmail("nudge", "body")

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "nudge", "body") }
        assertEquals(9, summary.captured.daysUnresolved)
        assertEquals(listOf("Google Analytics"), summary.captured.vendorNames, "the vendor is named so the fix is actionable")
        assertEquals(false, summary.captured.wrongSiteKey)
        // Claiming the nudge stamps "we told them", so the next unchanged re-scan stays quiet.
        verify(exactly = 1) { siteRepository.markBlockingAlertNotified(siteId, now, null) }
    }

    /** A brand-new problem gets a week to be fixed before we say anything. */
    @Test
    fun `an unchanged re-scan inside the grace period stays silent and only opens the streak`() {
        givenUnblockedScan()
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
        verify(exactly = 1) { siteRepository.startBlockingAlert(siteId, now) }
        verify(exactly = 0) { siteRepository.markBlockingAlertNotified(any(), any(), any()) }
    }

    /** Fixing it closes the streak, so the clock always measures how long *this* problem has stood. */
    @Test
    fun `a scan that comes back clean clears an open streak`() {
        givenScan(
            ScanTrigger.SCHEDULED,
            cookies = listOf("_ga"),
            trackerCount = 1,
            blocking = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 3),
        )
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(site(blockingAlertSince = now.minus(Duration.ofDays(30))))
        every { siteRepository.clearBlockingAlert(siteId) } returns 1

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { siteRepository.clearBlockingAlert(siteId) }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    /**
     * A manual re-scan mails nothing, but must still update the streak — otherwise a customer who fixes
     * their blocking and hits "Re-scan now" stays on a streak they already resolved and gets nagged.
     */
    @Test
    fun `a manual re-scan records the streak without mailing`() {
        givenUnblockedScan(ScanTrigger.MANUAL)

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.MANUAL)

        verify(exactly = 1) { siteRepository.startBlockingAlert(siteId, now) }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    /** A snippet carrying someone else's key is a different problem, and says so. */
    @Test
    fun `a site key mismatch nudges with the site-key variant`() {
        givenScan(
            ScanTrigger.SCHEDULED,
            cookies = listOf("_ga"),
            trackerCount = 1,
            blocking = WidgetProbe(installed = true, siteKeyMatched = false, blockedScriptCount = 0),
        )
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(site(blockingAlertSince = now.minus(Duration.ofDays(8))))
        val summary = slot<BlockingAlertSummary>()
        every { composer.blockingAlertEmail(any(), capture(summary)) } returns ComposedEmail("nudge", "body")

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 1) { sender.send("owner@example.com", "nudge", "body") }
        assertEquals(true, summary.captured.wrongSiteKey)
    }

    /** Never installed is onboarding's problem, not a blocking failure — we do not nag about it. */
    @Test
    fun `a site that never installed the widget is never nudged`() {
        givenScan(
            ScanTrigger.SCHEDULED,
            cookies = listOf("_ga"),
            trackerCount = 1,
            blocking = WidgetProbe.ABSENT,
        )
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(site(blockingAlertSince = now.minus(Duration.ofDays(60))))
        every { siteRepository.clearBlockingAlert(siteId) } returns 1

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    /** Two workers finishing a site's scans together must not send the same nudge twice. */
    @Test
    fun `a nudge claimed concurrently is not sent twice`() {
        givenUnblockedScan()
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(site(blockingAlertSince = now.minus(Duration.ofDays(9))))
        // The compare-and-set loses: someone else stamped the notification between our read and write.
        every { siteRepository.markBlockingAlertNotified(siteId, any(), any()) } returns 0

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    /** Once nudged, the same unbroken streak stays quiet until the repeat window passes. */
    @Test
    fun `a streak nudged a few days ago is not nudged again yet`() {
        givenUnblockedScan()
        givenPreviousScan(cookies = listOf("_ga"), trackerCount = 1)
        every { siteRepository.findById(siteId) } returns
            Optional.of(
                site(
                    blockingAlertSince = now.minus(Duration.ofDays(20)),
                    blockingAlertNotifiedAt = now.minus(Duration.ofDays(3)),
                ),
            )

        notifier.sendScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED)

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
        blocking: WidgetProbe? = null,
        observedTrackers: String? = null,
    ) {
        every { scanRepository.findById(scanId) } returns
            Optional.of(scan(scanId, trigger, trackerCount, createdAt = now, blocking = blocking, observedTrackers = observedTrackers))
        every { scanCookieRepository.findByScanId(scanId) } returns cookies.map(::cookie)
    }

    /**
     * A completed scan of a site that has our widget installed and is still letting Google Analytics
     * fire before consent — the exact BACKLOG #19 situation the nudge exists for.
     */
    private fun givenUnblockedScan(trigger: ScanTrigger = ScanTrigger.SCHEDULED) {
        givenScan(
            trigger,
            cookies = listOf("_ga"),
            trackerCount = 1,
            blocking = WidgetProbe(installed = true, siteKeyMatched = true, blockedScriptCount = 0),
            observedTrackers = "google-analytics.com",
        )
        every { trackerClassifier.describe(listOf("google-analytics.com")) } returns
            listOf(TrackerSignature(domain = "google-analytics.com", name = "Google Analytics", category = "analytics"))
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
        blocking: WidgetProbe? = null,
        observedTrackers: String? = null,
    ) = ScanEntity(
        id = id,
        siteId = siteId,
        status = ScanStatus.DONE,
        trigger = trigger,
        marketingTrackerCount = trackerCount,
        widgetDetected = blocking?.installed,
        widgetSiteKeyMatched = blocking?.siteKeyMatched,
        blockedScriptCount = blocking?.blockedScriptCount,
        observedTrackers = observedTrackers,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun cookie(name: String) = ScanCookieEntity(scanId = scanId, name = name)

    private fun site(
        status: SiteStatus = SiteStatus.ACTIVE,
        blockingAlertSince: Instant? = null,
        blockingAlertNotifiedAt: Instant? = null,
    ) = SiteEntity(
        id = siteId,
        userId = userId,
        domain = "shop.example.com",
        siteKey = "pk_test",
        status = status,
        blockingAlertSince = blockingAlertSince,
        blockingAlertNotifiedAt = blockingAlertNotifiedAt,
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
