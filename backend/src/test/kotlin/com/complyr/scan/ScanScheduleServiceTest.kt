package com.complyr.scan

import com.complyr.billing.AccountEntitlement
import com.complyr.billing.EntitlementService
import com.complyr.billing.Plan
import com.complyr.billing.RescanFrequency
import com.complyr.site.SiteEntity
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The customer-facing half of the re-scan schedule. What matters is that the date shown in the dashboard
 * is the SAME instant [ScheduledRescanJob] gates its nightly enqueue on, and that every case where the job
 * would never come back — an archived site, a lapsed account, a trial that ends before the next cycle — is
 * reported as "not scheduled" with a reason rather than as a date that will never arrive.
 *
 * Expected instants are written as literals rather than computed through [RescanCadence]: calling the
 * production helper to build the expectation would make the assertion agree with any answer it produced.
 */
class ScanScheduleServiceTest {
    private val siteRepository = mockk<SiteRepository>()
    private val scanRepository = mockk<ScanRepository>()
    private val entitlementService = mockk<EntitlementService>()
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val now: Instant = Instant.parse("2026-08-14T10:00:00Z")

    private val service =
        ScanScheduleService(
            siteRepository,
            scanRepository,
            entitlementService,
            Clock.fixed(now, zone),
        )

    private val userId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()

    @Test
    fun `another user's site is a 404, never a schedule`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns null

        assertThrows<SiteNotFoundException> { service.forSite(userId, siteId) }
    }

    @Test
    fun `a weekly plan schedules the next scan one week after the last one`() {
        val lastScanAt = Instant.parse("2026-08-12T10:00:00Z")
        stub(SiteStatus.ACTIVE, AccountEntitlement.Subscribed(Plan.PRO), lastScanAt)

        val schedule = service.forSite(userId, siteId)

        assertTrue(schedule.scheduled)
        assertEquals("weekly", schedule.frequency)
        assertNull(schedule.reason)
        assertEquals(
            Instant.parse("2026-08-19T10:00:00Z"),
            schedule.nextScanAt,
            "the promised date must be the exact instant the job treats as due",
        )
    }

    @Test
    fun `the promised date is the same instant the nightly job gates on`() {
        // The one contract that makes this endpoint worth having: dashboard and job read one definition.
        val lastScanAt = Instant.parse("2026-08-12T10:00:00Z")
        stub(SiteStatus.ACTIVE, AccountEntitlement.Subscribed(Plan.PRO), lastScanAt)

        val promised = service.forSite(userId, siteId).nextScanAt

        assertEquals(RescanCadence.dueAt(lastScanAt, RescanFrequency.WEEKLY, zone), promised)
    }

    @Test
    fun `an archived site is never re-scanned, so it carries no schedule`() {
        stub(SiteStatus.ARCHIVED, AccountEntitlement.Subscribed(Plan.PRO), now.minus(Duration.ofDays(2)))

        val schedule = service.forSite(userId, siteId)

        assertFalse(schedule.scheduled, "findRescanCandidates only selects active sites")
        assertEquals("archived", schedule.reason)
        assertNull(schedule.frequency)
        assertNull(schedule.nextScanAt)
    }

    @Test
    fun `a lapsed account has its scans frozen, so it carries no schedule`() {
        stub(SiteStatus.ACTIVE, AccountEntitlement.Expired, now.minus(Duration.ofDays(2)))

        val schedule = service.forSite(userId, siteId)

        // Expired entitlements still carry a MONTHLY cadence for the shared shape; surfacing it would
        // promise a scan the job explicitly skips ("no new sites, no scans").
        assertFalse(schedule.scheduled)
        assertEquals("lapsed", schedule.reason)
        assertNull(schedule.frequency)
        assertNull(schedule.nextScanAt)
    }

    @Test
    fun `a trial whose next cycle falls after it ends promises no date`() {
        // Trials borrow Starter's MONTHLY cadence but run 14 days: the site was scanned on signup day, so
        // the next cycle lands two weeks after the account has already lapsed. The job would skip it.
        stub(
            SiteStatus.ACTIVE,
            trial(endsAt = now.plus(Duration.ofDays(12))),
            lastScanAt = now.minus(Duration.ofDays(2)),
        )

        val schedule = service.forSite(userId, siteId)

        assertFalse(schedule.scheduled)
        assertEquals("trial_ends_first", schedule.reason)
        assertNull(schedule.nextScanAt)
    }

    @Test
    fun `a trial long enough to cover the next cycle still gets its date`() {
        // The guard is about the date, not about being on a trial: a subscription taken out mid-cycle
        // extends the entitlement, so a trial that outlasts the due instant is a promise we can keep.
        val lastScanAt = Instant.parse("2026-08-13T10:00:00Z")
        stub(SiteStatus.ACTIVE, trial(endsAt = Instant.parse("2026-10-01T00:00:00Z")), lastScanAt)

        val schedule = service.forSite(userId, siteId)

        assertTrue(schedule.scheduled)
        assertEquals("monthly", schedule.frequency)
        assertEquals(Instant.parse("2026-09-13T10:00:00Z"), schedule.nextScanAt)
    }

    @Test
    fun `a never-scanned trial site is due tonight, well inside the trial`() {
        stub(SiteStatus.ACTIVE, trial(endsAt = now.plus(Duration.ofDays(12))), lastScanAt = null)

        val schedule = service.forSite(userId, siteId)

        assertTrue(schedule.scheduled, "with no date to overshoot, the trial guard must not fire")
        assertNull(schedule.nextScanAt)
    }

    @Test
    fun `a never-scanned site is scheduled but has no date to promise`() {
        stub(SiteStatus.ACTIVE, AccountEntitlement.Subscribed(Plan.STARTER), lastScanAt = null)

        val schedule = service.forSite(userId, siteId)

        assertTrue(schedule.scheduled)
        assertEquals("monthly", schedule.frequency)
        assertNull(schedule.nextScanAt, "the job treats a never-scanned site as due now, not on a date")
    }

    @Test
    fun `the last scan is the most recent of any status, not the most recent completed one`() {
        // A queued re-scan pushes the next scheduled one out — the job's max(created_at) ignores status,
        // so a customer watching a scan run must not also be told the next one is already overdue.
        val queuedAt = Instant.parse("2026-08-14T09:55:00Z")
        stub(SiteStatus.ACTIVE, AccountEntitlement.Subscribed(Plan.PRO), queuedAt, ScanStatus.QUEUED)

        val schedule = service.forSite(userId, siteId)

        assertEquals(Instant.parse("2026-08-21T09:55:00Z"), schedule.nextScanAt)
    }

    @Test
    fun `a monthly cadence lands on the same day of the next month, not 30 days later`() {
        val lastScanAt = Instant.parse("2026-01-31T09:00:00Z")

        val dueAt = RescanCadence.dueAt(lastScanAt, RescanFrequency.MONTHLY, ZoneId.of("UTC"))

        // Calendar arithmetic clamps to the shortest month; a fixed 30-day Duration would give March 2nd.
        assertEquals(Instant.parse("2026-02-28T09:00:00Z"), dueAt)
    }

    private fun trial(endsAt: Instant) = AccountEntitlement.Trial(endsAt = endsAt, entitlements = Plan.STARTER.entitlements)

    private fun stub(
        status: SiteStatus,
        entitlement: AccountEntitlement,
        lastScanAt: Instant?,
        lastScanStatus: ScanStatus = ScanStatus.DONE,
    ) {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site(status)
        every { entitlementService.resolve(userId) } returns entitlement
        every { scanRepository.findFirstBySiteIdOrderByCreatedAtDesc(siteId) } returns
            lastScanAt?.let { scan(it, lastScanStatus) }
    }

    private fun site(status: SiteStatus) =
        SiteEntity(
            id = siteId,
            userId = userId,
            domain = "example.com",
            siteKey = "pk_AbC123",
            status = status,
            createdAt = now,
            updatedAt = now,
        )

    private fun scan(
        createdAt: Instant,
        status: ScanStatus,
    ) = ScanEntity(
        id = UUID.randomUUID(),
        siteId = siteId,
        status = status,
        trigger = ScanTrigger.SCHEDULED,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
