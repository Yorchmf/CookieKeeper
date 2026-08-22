package eu.cookiekeeper.scan

import eu.cookiekeeper.billing.AccountEntitlement
import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.billing.OnDemandRescanNotEntitledException
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class ScanRequestServiceTest {
    private val now: Instant = Instant.parse("2026-08-04T09:00:00Z")
    private val siteRepository = mockk<SiteRepository>()
    private val scanRepository = mockk<ScanRepository>(relaxed = true)
    private val entitlementService = mockk<EntitlementService>(relaxed = true)
    private val scanQueue = mockk<ScanQueue>()

    private val service =
        ScanRequestService(
            siteRepository,
            scanRepository,
            entitlementService,
            scanQueue,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val userId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()

    private fun site(status: SiteStatus = SiteStatus.ACTIVE) =
        SiteEntity(
            id = siteId,
            userId = userId,
            domain = "example.com",
            siteKey = "pk_AbC123",
            status = status,
            createdAt = now,
            updatedAt = now,
        )

    private fun stubSite(site: SiteEntity?) {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site
        // The status re-check under the per-site lock re-reads via the status projection, NOT another
        // findById — a second entity find would be served from the persistence context's identity map and
        // could never observe a concurrent erasure (see ScanRequestService.request / findStatusById docs).
        every { siteRepository.findStatusById(siteId) } returns site?.status
    }

    private fun stubLiveScan(exists: Boolean) {
        every { scanRepository.existsBySiteIdAndStatusIn(siteId, ScanRequestService.LIVE_STATUSES) } returns exists
    }

    @Test
    fun `an entitled Pro account gets a MANUAL scan enqueued at normal priority`() {
        stubSite(site())
        stubLiveScan(false)
        // Pro is entitled to on-demand rescan but not priority scanning, so the manual scan joins the
        // normal claim tier — the priority is resolved here, from the owner's plan, and passed to enqueue.
        every { entitlementService.resolve(userId) } returns AccountEntitlement.Subscribed(Plan.PRO)
        val scanId = UUID.randomUUID()
        every { scanQueue.enqueue(siteId, ScanTrigger.MANUAL, now, ScanQueue.PRIORITY_NORMAL) } returns scanId

        assertEquals(scanId, service.request(userId, siteId))
        // The lock must be taken before the live-scan read, or the check-then-enqueue is a race.
        verify { scanRepository.acquireSiteScanLock(any()) }
    }

    @Test
    fun `a Business account's MANUAL scan is enqueued at high priority`() {
        stubSite(site())
        stubLiveScan(false)
        every { entitlementService.resolve(userId) } returns AccountEntitlement.Subscribed(Plan.BUSINESS)
        val scanId = UUID.randomUUID()
        every { scanQueue.enqueue(siteId, ScanTrigger.MANUAL, now, ScanQueue.PRIORITY_HIGH) } returns scanId

        assertEquals(scanId, service.request(userId, siteId))
        // Business grants priorityScan, so the queue must receive the high tier — not the normal default.
        verify { scanQueue.enqueue(siteId, ScanTrigger.MANUAL, now, ScanQueue.PRIORITY_HIGH) }
    }

    @Test
    fun `a plan without on-demand rescan is refused before any scan state is read`() {
        // Ordering matters: a Starter user must see the same upgrade prompt whether or not a scan happens
        // to be running, otherwise the 409 leaks that the feature would have worked.
        stubSite(site())
        every { entitlementService.requireOnDemandRescan(userId) } throws OnDemandRescanNotEntitledException()

        assertThrows<OnDemandRescanNotEntitledException> { service.request(userId, siteId) }
        verify(exactly = 0) { scanRepository.existsBySiteIdAndStatusIn(any(), any()) }
        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `a site with a live scan is a 409, not a second queued crawl`() {
        stubSite(site())
        stubLiveScan(true)

        assertThrows<ScanAlreadyInProgressException> { service.request(userId, siteId) }
        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `another user's site is a 404, never a 403`() {
        stubSite(null)

        assertThrows<SiteNotFoundException> { service.request(userId, siteId) }
        // Not even the entitlement is consulted — nothing about the caller's plan leaks on a foreign id.
        verify(exactly = 0) { entitlementService.requireOnDemandRescan(any()) }
        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `an archived site is a 404 — it has no widget and nothing to keep current`() {
        stubSite(site(status = SiteStatus.ARCHIVED))

        assertThrows<SiteNotFoundException> { service.request(userId, siteId) }
        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }

    @Test
    fun `a site archived by a concurrent account erasure after the ownership check is still a 404`() {
        // The ownership lookup (findByIdAndUserId) sees the site ACTIVE — an erasure hadn't committed
        // yet — but by the time this request takes the per-site lock, AccountDeletionService's own
        // per-user-locked transaction has archived it. The status projection re-read under the lock,
        // which (unlike a second findById) genuinely re-queries past the identity map, must catch this.
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site(status = SiteStatus.ACTIVE)
        every { siteRepository.findStatusById(siteId) } returns SiteStatus.ARCHIVED

        assertThrows<SiteNotFoundException> { service.request(userId, siteId) }
        verify(exactly = 0) { scanQueue.enqueue(any(), any(), any(), any()) }
    }
}
