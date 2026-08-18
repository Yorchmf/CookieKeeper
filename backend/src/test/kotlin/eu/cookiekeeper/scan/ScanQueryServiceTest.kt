package eu.cookiekeeper.scan

import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The read side's per-row "+N new" history badge — `newCookieCountsWithinPage`. This is the subtlest new
 * code: a single batch read diffed adjacent-`done`-scans WITHIN the page, so the cases that matter are the
 * boundaries — the oldest scan on the page (no in-page predecessor → no badge), a non-`done` scan skipped as
 * a baseline, an empty-cookie scan, and the &lt;2-`done` short-circuit. Ownership gating is covered too, since
 * every read must be indistinguishable from a miss on a foreign id.
 */
class ScanQueryServiceTest {
    private val siteRepository = mockk<SiteRepository>()
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val scanDiffCalculator = ScanDiffCalculator(scanRepository, scanCookieRepository)
    private val now: Instant = Instant.parse("2026-08-14T10:00:00Z")

    private val service =
        ScanQueryService(
            siteRepository,
            scanRepository,
            scanCookieRepository,
            scanDiffCalculator,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private val userId: UUID = UUID.randomUUID()
    private val siteId: UUID = UUID.randomUUID()

    @Test
    fun `another user's site is a 404 before any scan is read`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns null

        assertThrows<SiteNotFoundException> { service.list(userId, siteId, 10) }
    }

    @Test
    fun `each done scan counts cookies its in-page predecessor lacked, the oldest gets no badge`() {
        // Newest-first, as the repository returns them.
        val newest = doneScan(minutesAgo = 0)
        val middle = doneScan(minutesAgo = 10)
        val oldest = doneScan(minutesAgo = 20)
        stubList(newest, middle, oldest)
        stubCookies(
            oldest.id to listOf("_ga"),
            middle.id to listOf("_ga", "_fbp"),
            newest.id to listOf("_ga", "_fbp", "sid"),
        )

        val counts = service.list(userId, siteId, 10).associate { it.id to it.newCookieCount }

        assertNull(counts[oldest.id], "oldest scan on the page has no in-page baseline — no badge")
        assertEquals(1, counts[middle.id], "middle added _fbp over the oldest")
        assertEquals(1, counts[newest.id], "newest added sid over the middle")
    }

    @Test
    fun `a non-done scan is skipped as a baseline, not diffed against`() {
        val newest = doneScan(minutesAgo = 0)
        val running = scan(ScanStatus.RUNNING, minutesAgo = 10)
        val oldest = doneScan(minutesAgo = 20)
        stubList(newest, running, oldest)
        // The newest must diff against the prior *done* scan (oldest), not the interleaved running one.
        stubCookies(
            oldest.id to listOf("_ga"),
            newest.id to listOf("_ga", "_fbp"),
        )

        val counts = service.list(userId, siteId, 10).associate { it.id to it.newCookieCount }

        assertNull(counts[running.id], "a running scan carries no diff")
        assertNull(counts[oldest.id], "oldest done scan on the page has no in-page baseline")
        assertEquals(1, counts[newest.id], "_fbp is new over the previous done scan")
    }

    @Test
    fun `a done scan that lost all its cookies reports zero new, not a crash`() {
        val newest = doneScan(minutesAgo = 0)
        val oldest = doneScan(minutesAgo = 10)
        stubList(newest, oldest)
        stubCookies(
            oldest.id to listOf("_ga", "_fbp"),
            newest.id to emptyList(),
        )

        val counts = service.list(userId, siteId, 10).associate { it.id to it.newCookieCount }

        assertEquals(0, counts[newest.id], "nothing added — every cookie was removed, not new")
        assertNull(counts[oldest.id])
    }

    @Test
    fun `a single done scan has nothing to compare and skips the batch read entirely`() {
        val only = doneScan(minutesAgo = 0)
        val queued = scan(ScanStatus.QUEUED, minutesAgo = 5)
        stubList(queued, only)
        // No findByScanIdIn stub: with fewer than two done scans the walk must short-circuit before reading.

        val counts = service.list(userId, siteId, 10).associate { it.id to it.newCookieCount }

        assertNull(counts[only.id])
        assertNull(counts[queued.id])
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun stubList(vararg scans: ScanEntity) {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site()
        every { scanRepository.findBySiteIdOrderByCreatedAtDesc(siteId, any()) } returns scans.toList()
    }

    private fun stubCookies(vararg cookiesByScan: Pair<UUID, List<String>>) {
        val rows = cookiesByScan.flatMap { (id, names) -> names.map { cookie(id, it) } }
        every { scanCookieRepository.findByScanIdIn(any()) } returns rows
    }

    private fun site() =
        SiteEntity(
            id = siteId,
            userId = userId,
            domain = "example.com",
            siteKey = "pk_AbC123",
            status = SiteStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )

    private fun doneScan(minutesAgo: Long) = scan(ScanStatus.DONE, minutesAgo)

    private fun scan(
        status: ScanStatus,
        minutesAgo: Long,
    ): ScanEntity {
        val at = now.minus(Duration.ofMinutes(minutesAgo))
        return ScanEntity(
            id = UUID.randomUUID(),
            siteId = siteId,
            status = status,
            trigger = ScanTrigger.SCHEDULED,
            marketingTrackerCount = 0,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun cookie(
        scanId: UUID,
        name: String,
    ) = ScanCookieEntity(scanId = scanId, name = name)
}
