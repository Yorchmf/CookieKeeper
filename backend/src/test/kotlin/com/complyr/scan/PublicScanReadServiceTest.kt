package com.complyr.scan

import com.complyr.scan.dto.PublicScanReportRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The public, token-scoped read side: the coarse teaser, the email-gated report unlock, and — the
 * security-critical part — that an unknown, expired, or honeypot token is indistinguishable (a single
 * generic not-found), never a bot-detection oracle. Repositories are faked so this stays a fast unit
 * test of the disclosure/authorization logic.
 */
class PublicScanReadServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<PublicScanRepository>()
    private val cookieRepository = mockk<PublicScanCookieRepository>()
    private val service = PublicScanReadService(repository, cookieRepository, clock)

    private fun scan(
        token: String,
        status: ScanStatus,
        expiresAt: Instant = now.plus(Duration.ofDays(7)),
    ): PublicScanEntity =
        PublicScanEntity(
            id = UUID.randomUUID(),
            domain = "acme.example.com",
            status = status,
            publicToken = token,
            createdAt = now,
            updatedAt = now,
            expiresAt = expiresAt,
        )

    private fun cookie(
        scanId: UUID,
        name: String,
        category: String?,
        isKnown: Boolean,
    ): PublicScanCookieEntity =
        PublicScanCookieEntity(
            publicScanId = scanId,
            name = name,
            category = category,
            provider = if (isKnown) "Some Vendor" else null,
            isKnown = isKnown,
        )

    @Test
    fun `the teaser of a completed scan reports counts by category and needs-review, but no cookie names`() {
        val done = scan("tok_done", ScanStatus.DONE)
        every { repository.findByPublicToken("tok_done") } returns done
        every { cookieRepository.findByPublicScanId(done.id) } returns
            listOf(
                cookie(done.id, "_ga", "statistics", isKnown = true),
                cookie(done.id, "_gid", "statistics", isKnown = true),
                cookie(done.id, "_fbp", "marketing", isKnown = true),
                cookie(done.id, "mystery", category = null, isKnown = false),
            )

        val teaser = service.teaser("tok_done")

        assertEquals("done", teaser.status)
        val verdict = requireNotNull(teaser.verdict)
        assertEquals(4, verdict.totalCookies)
        assertEquals(mapOf("statistics" to 2, "marketing" to 1), verdict.cookiesByCategory)
        assertEquals(1, verdict.needsReviewCount)
    }

    @Test
    fun `the teaser verdict carries the indicative compliance report`() {
        // The free verdict now surfaces the same ComplianceAnalyzer output that grades the authenticated
        // scan — a score plus severity-ranked issue codes (no cookie names) — so the funnel can show the
        // visitor what's wrong and how bad, not just a raw count.
        val done = scan("tok_score", ScanStatus.DONE)
        every { repository.findByPublicToken("tok_score") } returns done
        every { cookieRepository.findByPublicScanId(done.id) } returns
            listOf(
                cookie(done.id, "_ga", "statistics", isKnown = true),
                cookie(done.id, "_fbp", "marketing", isKnown = true),
            )

        val verdict = requireNotNull(service.teaser("tok_score").verdict)

        val compliance = verdict.compliance
        assertTrue(compliance.score < 100, "pre-consent findings must pull the score below a clean 100")
        assertTrue(compliance.issues.any { it.code == "pre_consent_tracking" })
        assertTrue(compliance.issues.any { it.code == "marketing_cookies" })
    }

    @Test
    fun `the teaser of a not-yet-done scan is status-only and never queries cookies`() {
        val queued = scan("tok_queued", ScanStatus.QUEUED)
        every { repository.findByPublicToken("tok_queued") } returns queued

        val teaser = service.teaser("tok_queued")

        assertEquals("queued", teaser.status)
        assertNull(teaser.verdict, "no verdict until the crawl completes")
        verify(exactly = 0) { cookieRepository.findByPublicScanId(any()) }
    }

    @Test
    fun `an unknown token yields the generic not-found`() {
        every { repository.findByPublicToken("nope") } returns null

        assertThrows<PublicScanNotFoundException> { service.teaser("nope") }
    }

    @Test
    fun `an expired token yields the same generic not-found as an unknown one (no honeypot oracle)`() {
        val expired = scan("tok_expired", ScanStatus.DONE, expiresAt = now.minus(Duration.ofSeconds(1)))
        every { repository.findByPublicToken("tok_expired") } returns expired

        assertThrows<PublicScanNotFoundException> { service.teaser("tok_expired") }
        // An expired row must not leak its findings via the teaser.
        verify(exactly = 0) { cookieRepository.findByPublicScanId(any()) }
    }

    @Test
    fun `unlocking the report captures the email on the row and returns the full per-cookie detail`() {
        val done = scan("tok_done", ScanStatus.DONE)
        every { repository.findByPublicToken("tok_done") } returns done
        val saved = slot<PublicScanEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }
        every { cookieRepository.findByPublicScanId(done.id) } returns
            listOf(cookie(done.id, "_ga", "statistics", isKnown = true))

        val report = service.unlockReport("tok_done", PublicScanReportRequest("lead@example.com"))

        // The lead was persisted immutably (copy + save), leaving the token/expiry untouched.
        assertEquals("lead@example.com", saved.captured.email)
        assertEquals("tok_done", saved.captured.publicToken)
        // The gated payload carries the cookie names the teaser withheld.
        assertEquals(listOf("_ga"), report.cookiesByCategory["statistics"]?.map { it.name })
    }

    @Test
    fun `unlocking the report for an unknown token never writes and yields the generic not-found`() {
        every { repository.findByPublicToken("nope") } returns null

        assertThrows<PublicScanNotFoundException> {
            service.unlockReport("nope", PublicScanReportRequest("lead@example.com"))
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `unlocking the report for an expired token never writes the lead`() {
        val expired = scan("tok_expired", ScanStatus.DONE, expiresAt = now.minus(Duration.ofSeconds(1)))
        every { repository.findByPublicToken("tok_expired") } returns expired

        assertThrows<PublicScanNotFoundException> {
            service.unlockReport("tok_expired", PublicScanReportRequest("lead@example.com"))
        }
        verify(exactly = 0) { repository.save(any()) }
    }
}
