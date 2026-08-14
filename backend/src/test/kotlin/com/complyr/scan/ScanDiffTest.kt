package com.complyr.scan

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scan-to-scan diff — the shared definition of "what changed since the previous scan" that both the
 * scan-complete email gate and the dashboard read from. Compared by cookie NAME (every scan writes its own
 * rows) and by tracker count. The cases that matter are the boundaries: no baseline, added, removed, and a
 * tracker-only change with identical cookies.
 */
class ScanDiffTest {
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val calculator = ScanDiffCalculator(scanRepository, scanCookieRepository)

    private val siteId: UUID = UUID.randomUUID()
    private val scanId: UUID = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-08-13T10:00:00Z")

    // ---- ScanDiff (pure model) ---------------------------------------------------------------

    @Test
    fun `between reports added and removed cookies as sorted directional sets`() {
        val diff =
            ScanDiff.between(
                currentCookieNames = listOf("_ga", "_fbp", "sid"),
                currentTrackerCount = 3,
                previous =
                    PreviousScan(
                        scanId = UUID.randomUUID(),
                        scanAt = now.minus(Duration.ofDays(7)),
                        cookieNames = listOf("sid", "_hjid"),
                        trackerCount = 1,
                    ),
            )

        assertEquals(listOf("_fbp", "_ga"), diff.addedCookieNames, "added = current minus previous, sorted")
        assertEquals(listOf("_hjid"), diff.removedCookieNames, "removed = previous minus current, sorted")
        assertEquals(2, diff.newCookieCount)
        assertEquals(1, diff.removedCookieCount)
        assertEquals(2, diff.trackerCountDelta, "3 trackers now vs 1 before")
        assertTrue(diff.hasPrevious)
        assertTrue(diff.hasNewFindings)
    }

    @Test
    fun `baseline has no previous and no comparison`() {
        val diff = ScanDiff.baseline(currentTrackerCount = 2)

        assertFalse(diff.hasPrevious)
        assertNull(diff.previousScanId)
        assertNull(diff.trackerCountDelta)
        assertEquals(0, diff.newCookieCount)
        assertEquals(0, diff.removedCookieCount)
        // No baseline still counts as "new findings" so the first scheduled scan is never silently dropped.
        assertTrue(diff.hasNewFindings)
    }

    @Test
    fun `identical cookies and tracker count are not new findings`() {
        val diff =
            ScanDiff.between(
                currentCookieNames = listOf("_ga", "_fbp"),
                currentTrackerCount = 2,
                previous =
                    PreviousScan(
                        scanId = UUID.randomUUID(),
                        scanAt = now.minus(Duration.ofDays(7)),
                        cookieNames = listOf("_fbp", "_ga"),
                        trackerCount = 2,
                    ),
            )

        assertFalse(diff.hasNewFindings)
        assertEquals(0, diff.newCookieCount)
        assertEquals(0, diff.removedCookieCount)
        assertEquals(0, diff.trackerCountDelta)
    }

    @Test
    fun `a changed tracker count is a new finding even when the cookies are identical`() {
        val diff =
            ScanDiff.between(
                currentCookieNames = listOf("_ga"),
                currentTrackerCount = 4,
                previous =
                    PreviousScan(
                        scanId = UUID.randomUUID(),
                        scanAt = now.minus(Duration.ofDays(7)),
                        cookieNames = listOf("_ga"),
                        trackerCount = 1,
                    ),
            )

        assertTrue(diff.hasNewFindings)
        assertEquals(0, diff.newCookieCount, "no cookie added — only the tracker count moved")
        assertEquals(3, diff.trackerCountDelta)
    }

    // ---- ScanDiffCalculator (orchestration) --------------------------------------------------

    @Test
    fun `forScan diffs against the previous completed scan`() {
        val previousId = UUID.randomUUID()
        val previousAt = now.minus(Duration.ofDays(7))
        every {
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                siteId,
                ScanStatus.DONE,
                now,
            )
        } returns scan(previousId, trackerCount = 1, createdAt = previousAt)
        every { scanCookieRepository.findByScanId(previousId) } returns listOf(cookie("_ga"))

        val diff = calculator.forScan(scan(scanId, trackerCount = 2, createdAt = now), listOf("_ga", "_fbp"), 2)

        assertEquals(previousId, diff.previousScanId)
        assertEquals(previousAt, diff.previousScanAt)
        assertEquals(listOf("_fbp"), diff.addedCookieNames)
        assertEquals(emptyList(), diff.removedCookieNames)
        assertEquals(1, diff.trackerCountDelta)
    }

    @Test
    fun `forScan returns a baseline when the site has no earlier completed scan`() {
        every {
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                siteId,
                ScanStatus.DONE,
                now,
            )
        } returns null

        val diff = calculator.forScan(scan(scanId, trackerCount = 2, createdAt = now), listOf("_ga"), 2)

        assertFalse(diff.hasPrevious)
        assertTrue(diff.hasNewFindings)
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private fun scan(
        id: UUID,
        trackerCount: Int,
        createdAt: Instant,
    ) = ScanEntity(
        id = id,
        siteId = siteId,
        status = ScanStatus.DONE,
        trigger = ScanTrigger.SCHEDULED,
        marketingTrackerCount = trackerCount,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun cookie(name: String) = ScanCookieEntity(scanId = UUID.randomUUID(), name = name)
}
