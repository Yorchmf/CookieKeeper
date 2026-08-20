package eu.cookiekeeper.scan

import eu.cookiekeeper.site.ConsentBasisService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The bridge from "a scan finished" to "visitors may need asking again" (BACKLOG #18). It owns two
 * decisions worth pinning down: it reports the *decidable* categories a scan put in use, and it never
 * lets its own failure escape into the already-committed scan transaction.
 */
class ScanConsentBasisListenerTest {
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val consentBasisService = mockk<ConsentBasisService>(relaxed = true)
    private val listener = ScanConsentBasisListener(scanRepository, scanCookieRepository, consentBasisService)

    private val siteId: UUID = UUID.randomUUID()
    private val scanId: UUID = UUID.randomUUID()

    @Test
    fun `reports the categories the completed scan put in use`() {
        every { scanRepository.findById(scanId) } returns Optional.of(scan(trackerCount = 1))
        every { scanCookieRepository.findByScanId(scanId) } returns
            listOf(
                ScanCookieEntity(scanId = scanId, name = "sid", category = "necessary"),
                ScanCookieEntity(scanId = scanId, name = "_ga", category = "statistics"),
                ScanCookieEntity(scanId = scanId, name = "wp_x", category = null),
            )

        listener.onScanCompleted(ScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED))

        // marketing from the tracker count, statistics from the cookie; necessary and the
        // unclassified cookie are not things a visitor can decide.
        verify { consentBasisService.record(siteId, setOf("statistics", "marketing")) }
    }

    @Test
    fun `swallows its own failure rather than unwinding a committed scan`() {
        // Rethrowing here would roll back `markSucceeded` and leave the job leased, re-crawling the
        // site. A dropped observation is recoverable — the next scan sees the same category.
        every { scanRepository.findById(scanId) } throws IllegalStateException("db gone")

        listener.onScanCompleted(ScanCompleted(scanId, siteId, ScanTrigger.SCHEDULED))
    }

    @Test
    fun `ignores an event whose scan has since disappeared`() {
        every { scanRepository.findById(scanId) } returns Optional.empty()

        listener.onScanCompleted(ScanCompleted(scanId, siteId, ScanTrigger.MANUAL))

        verify(exactly = 0) { consentBasisService.record(any(), any()) }
    }

    private fun scan(trackerCount: Int) =
        ScanEntity(
            id = scanId,
            siteId = siteId,
            status = ScanStatus.DONE,
            trigger = ScanTrigger.SCHEDULED,
            marketingTrackerCount = trackerCount,
            createdAt = Instant.parse("2026-08-20T09:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T09:01:00Z"),
        )
}
