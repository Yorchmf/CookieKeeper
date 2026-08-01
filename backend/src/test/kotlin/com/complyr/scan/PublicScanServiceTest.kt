package com.complyr.scan

import com.complyr.scan.dto.PublicScanRequest
import com.complyr.site.InvalidDomainException
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

/**
 * The web-tier funnel entry point: domain normalization, the 24h per-domain cache decision, and the
 * enqueue-or-reuse branch. The queue and repository are faked so this stays a fast unit test of the
 * decision logic; the queue mechanics and the cache finder are covered by their own integration tests.
 */
class PublicScanServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<PublicScanRepository>()
    private val queue = mockk<PublicScanQueue>()
    private val service = PublicScanService(repository, queue, clock)

    private fun scan(
        domain: String,
        token: String,
        status: ScanStatus,
    ): PublicScanEntity =
        PublicScanEntity(
            id = UUID.randomUUID(),
            domain = domain,
            status = status,
            publicToken = token,
            createdAt = now,
            updatedAt = now,
            expiresAt = now,
        )

    @Test
    fun `messy input is normalized before the cache lookup and enqueue`() {
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        } returns null
        every { queue.enqueue(any(), any()) } returns "tok_new"

        val response = service.request(PublicScanRequest("HTTPS://Acme.Example.COM/pricing?x=1"))

        assertEquals("tok_new", response.token)
        assertEquals("queued", response.status)
        // Both the cache probe and the enqueue see the bare lowercase host, never the raw URL.
        verify {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "acme.example.com",
                ScanStatus.DONE,
                now.minusSeconds(24 * 3600),
            )
        }
        verify { queue.enqueue("acme.example.com", null) }
    }

    @Test
    fun `a fresh cached done scan is reused instead of enqueuing a new crawl`() {
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "acme.example.com",
                ScanStatus.DONE,
                now.minusSeconds(24 * 3600),
            )
        } returns scan("acme.example.com", "tok_cached", ScanStatus.DONE)

        val response = service.request(PublicScanRequest("acme.example.com"))

        assertEquals("tok_cached", response.token, "the cached scan's token is returned")
        assertEquals("done", response.status)
        verify(exactly = 0) { queue.enqueue(any(), any()) }
    }

    @Test
    fun `an invalid domain is rejected before touching the cache or queue`() {
        assertThrows<InvalidDomainException> { service.request(PublicScanRequest("not a domain")) }

        verify(exactly = 0) {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        }
        verify(exactly = 0) { queue.enqueue(any(), any()) }
    }
}
