package eu.cookiekeeper.scan

import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.IpHasher
import eu.cookiekeeper.scan.dto.PublicScanRequest
import eu.cookiekeeper.site.InvalidDomainException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The web-tier funnel entry point: domain normalization, the honeypot short-circuit, `ip_hash`
 * wiring, the per-requester concurrency cap, and the 24h cache enqueue-or-reuse branch. The queue,
 * repository, and IP hasher are faked so this stays a fast unit test of the decision logic; the queue
 * mechanics, the cache finder, and the real rotating-salt hashing are covered by their own tests.
 */
class PublicScanServiceTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<PublicScanRepository>()
    private val queue = mockk<PublicScanQueue>()
    private val ipHasher = mockk<IpHasher>()
    private val properties = propertiesWith(maxConcurrentScansPerIp = 3)
    private val service = PublicScanService(repository, queue, ipHasher, properties, clock)

    private val cacheWindowStart: Instant = now.minus(Duration.ofHours(24))

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
    fun `a filled honeypot short-circuits to a throwaway queued token without touching cache or queue`() {
        val response = service.request(PublicScanRequest(domain = "acme.example.com", website = "http://spam.example"), "1.2.3.4")

        assertEquals("queued", response.status)
        // A plausible token so the bot gets no signal, but nothing was validated, hashed, or persisted.
        verify(exactly = 0) { ipHasher.hash(any()) }
        verify(exactly = 0) {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        }
        verify(exactly = 0) { queue.enqueue(any(), any()) }
        verify(exactly = 0) { queue.reuseCachedResult(any(), any()) }
    }

    @Test
    fun `messy input is normalized, the IP is hashed, and both cache probe and enqueue see the bare host`() {
        every { ipHasher.hash("1.2.3.4") } returns "iphash"
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        } returns null
        every { repository.countByIpHashAndStatusIn("iphash", any()) } returns 0
        every { queue.enqueue(any(), any()) } returns "tok_new"

        val response = service.request(PublicScanRequest("HTTPS://Acme.Example.COM/pricing?x=1"), "1.2.3.4")

        assertEquals("tok_new", response.token)
        assertEquals("queued", response.status)
        verify {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "acme.example.com",
                ScanStatus.DONE,
                cacheWindowStart,
            )
        }
        // The enqueue carries the hashed IP, never the raw one.
        verify { queue.enqueue("acme.example.com", "iphash") }
    }

    @Test
    fun `a fresh cached done scan is reused as a new per-visitor result and is exempt from the concurrency cap`() {
        val cached = scan("acme.example.com", "tok_cached", ScanStatus.DONE)
        every { ipHasher.hash("1.2.3.4") } returns "iphash"
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "acme.example.com",
                ScanStatus.DONE,
                cacheWindowStart,
            )
        } returns cached
        every { queue.reuseCachedResult(cached, "iphash") } returns "tok_reused"

        val response = service.request(PublicScanRequest("acme.example.com"), "1.2.3.4")

        // The visitor gets their OWN fresh token, not the shared cached one — never the same identity.
        assertEquals("tok_reused", response.token, "a fresh per-visitor token backed by the cached crawl")
        assertEquals("done", response.status)
        verify(exactly = 1) { queue.reuseCachedResult(cached, "iphash") }
        verify(exactly = 0) { queue.enqueue(any(), any()) }
        // A cache hit enqueues no crawl, so it must not consume the per-requester cap.
        verify(exactly = 0) { repository.countByIpHashAndStatusIn(any(), any()) }
    }

    @Test
    fun `a requester at the concurrency cap is rejected before enqueuing`() {
        every { ipHasher.hash("1.2.3.4") } returns "iphash"
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        } returns null
        every { repository.countByIpHashAndStatusIn("iphash", any()) } returns 3

        assertThrows<PublicScanCapacityException> { service.request(PublicScanRequest("acme.example.com"), "1.2.3.4") }

        verify(exactly = 0) { queue.enqueue(any(), any()) }
    }

    @Test
    fun `a null ip_hash skips the concurrency cap and still enqueues`() {
        // Blank/absent source IP → no hash → the cap can't attribute the request, so it is skipped
        // (rate-limit tier + edge controls still apply). The scan is enqueued with a null ip_hash.
        every { ipHasher.hash(null) } returns null
        every {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        } returns null
        every { queue.enqueue("acme.example.com", null) } returns "tok_new"

        val response = service.request(PublicScanRequest("acme.example.com"), null)

        assertEquals("tok_new", response.token)
        verify(exactly = 0) { repository.countByIpHashAndStatusIn(any(), any()) }
        verify { queue.enqueue("acme.example.com", null) }
    }

    @Test
    fun `an invalid domain is rejected before touching the cache or queue`() {
        assertThrows<InvalidDomainException> { service.request(PublicScanRequest("not a domain"), "1.2.3.4") }

        verify(exactly = 0) {
            repository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(any(), any(), any())
        }
        verify(exactly = 0) { queue.enqueue(any(), any()) }
    }

    private fun propertiesWith(maxConcurrentScansPerIp: Int): CookieKeeperProperties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            scan = CookieKeeperProperties.Scan(maxConcurrentScansPerIp = maxConcurrentScansPerIp),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )
}
