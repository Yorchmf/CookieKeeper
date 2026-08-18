package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class PublicScanRepositoryIntegrationTest {
    @Autowired
    private lateinit var publicScanRepository: PublicScanRepository

    @Autowired
    private lateinit var publicScanCookieRepository: PublicScanCookieRepository

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun newScan(
        domain: String = "example.com",
        token: String = "tok_${UUID.randomUUID()}",
        createdAt: Instant = now,
        status: ScanStatus = ScanStatus.QUEUED,
    ): PublicScanEntity =
        publicScanRepository.saveAndFlush(
            PublicScanEntity(
                domain = domain,
                publicToken = token,
                status = status,
                createdAt = createdAt,
                updatedAt = createdAt,
                expiresAt = createdAt.plus(Duration.ofDays(7)),
            ),
        )

    private fun newCookie(
        scanId: UUID,
        name: String,
    ): PublicScanCookieEntity =
        publicScanCookieRepository.saveAndFlush(
            PublicScanCookieEntity(publicScanId = scanId, name = name),
        )

    @Test
    fun `persists a public scan and reads it back by its opaque token`() {
        val scan = newScan(token = "tok_read_me")

        val found = publicScanRepository.findByPublicToken("tok_read_me")

        assertEquals(scan.id, found?.id)
        assertEquals(ScanStatus.QUEUED, found?.status, "a fresh scan starts queued")
        assertNull(publicScanRepository.findByPublicToken("tok_unknown"), "an unknown token misses")
    }

    @Test
    fun `public_token is unique across scans`() {
        newScan(token = "tok_dupe")

        // Two anonymous scans must never collide on the read capability.
        assertThrows<DataIntegrityViolationException> { newScan(token = "tok_dupe") }
    }

    @Test
    fun `domain cache finder returns the most recent done scan inside the window and ignores older ones`() {
        val stale =
            newScan(domain = "shop.example", createdAt = now.minus(Duration.ofHours(30)), status = ScanStatus.DONE)
        val fresh =
            newScan(domain = "shop.example", createdAt = now.minus(Duration.ofMinutes(10)), status = ScanStatus.DONE)
        newScan(domain = "other.example", createdAt = now.minus(Duration.ofMinutes(5)), status = ScanStatus.DONE)

        val cutoff = now.minus(Duration.ofHours(24))
        val hit =
            publicScanRepository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "shop.example",
                ScanStatus.DONE,
                cutoff,
            )

        assertEquals(fresh.id, hit?.id, "returns the newest completed scan for this domain within the window")
        assertTrue(stale.createdAt.isBefore(cutoff), "the 30h-old scan is outside the 24h window and excluded")
    }

    @Test
    fun `domain cache finder ignores a recent scan that has not completed`() {
        // A failed/running scan within the window must NOT be served as a cached verdict, nor pin the
        // domain for 24h with no retry — the cache only reuses a completed (DONE) result.
        newScan(domain = "flaky.example", createdAt = now.minus(Duration.ofMinutes(10)), status = ScanStatus.FAILED)
        newScan(domain = "flaky.example", createdAt = now.minus(Duration.ofMinutes(5)), status = ScanStatus.RUNNING)

        val cutoff = now.minus(Duration.ofHours(24))
        val hit =
            publicScanRepository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "flaky.example",
                ScanStatus.DONE,
                cutoff,
            )

        assertNull(hit, "no completed scan in-window -> re-crawl instead of serving a failed/running row")
    }

    @Test
    fun `domain cache finder misses when every done scan for the domain is older than the window`() {
        newScan(domain = "cold.example", createdAt = now.minus(Duration.ofHours(48)), status = ScanStatus.DONE)

        val cutoff = now.minus(Duration.ofHours(24))
        val hit =
            publicScanRepository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                "cold.example",
                ScanStatus.DONE,
                cutoff,
            )

        assertNull(hit, "nothing fresh enough to serve from cache -> re-crawl")
    }

    @Test
    fun `deleting a public scan cascades to its cookies`() {
        val scan = newScan(token = "tok_cascade")
        newCookie(scan.id, "_ga")
        newCookie(scan.id, "_fbp")

        publicScanRepository.delete(scan)
        publicScanRepository.flush()

        assertTrue(
            publicScanCookieRepository.findByPublicScanId(scan.id).isEmpty(),
            "FK ON DELETE CASCADE removes the scan's cookies",
        )
    }

    @Test
    fun `deleteByPublicScanId drops a prior attempt's cookies so a re-run replaces them`() {
        val scan = newScan(token = "tok_rerun")
        newCookie(scan.id, "old_a")
        newCookie(scan.id, "old_b")

        val removed = publicScanCookieRepository.deleteByPublicScanId(scan.id)
        newCookie(scan.id, "fresh")

        assertEquals(2, removed, "both prior cookies were dropped")
        assertEquals(listOf("fresh"), publicScanCookieRepository.findByPublicScanId(scan.id).map { it.name })
    }
}
