package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The batch cap in isolation, pinned to a tiny [complyr.scan.rescan-batch-size] so a handful of due
 * sites overflows it — the prod default (200) would never trip in a test. The cap plus oldest-first
 * ordering is what lets a backlog drain over successive nightly runs instead of hitting the single
 * Chromium worker as one burst, so a regression that dropped the `LIMIT` must fail the build.
 *
 * Separate class from [ScheduledRescanJobTest] because it needs its own property override; the same
 * archive-everything isolation applies.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "complyr.scan.rescan-cron=-",
        "complyr.scan.rescan-batch-size=2",
    ],
)
class ScheduledRescanJobBatchTest {
    @Autowired
    private lateinit var job: ScheduledRescanJob

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE jobs, scans CASCADE")
        jdbcTemplate.update("UPDATE sites SET status = 'archived'")
    }

    @Test
    fun `a single run enqueues no more than the batch size`() {
        // Four due Pro sites, batch capped at 2: one run must enqueue exactly 2.
        val sites = (1..4).map { seedDueProSite() }

        job.enqueueDueRescans()

        val enqueued = sites.count { scheduledScanCount(it) == 1 }
        assertEquals(2, enqueued, "the LIMIT caps a single run at the batch size, deferring the rest")
    }

    private fun seedDueProSite(): UUID {
        val userId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')",
            userId,
            "batch-$userId@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO subscriptions (id, user_id, plan, status) VALUES (?, ?, 'PRO', 'active')",
            UUID.randomUUID(),
            userId,
        )
        val siteId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sites (id, user_id, domain, site_key, status) VALUES (?, ?, ?, ?, 'active')",
            siteId,
            userId,
            "site-$siteId.example.com",
            "pk_$siteId",
        )
        val at = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(40)), ZoneOffset.UTC)
        jdbcTemplate.update(
            "INSERT INTO scans (id, site_id, status, trigger_source, created_at, updated_at) " +
                "VALUES (?, ?, 'done', 'site_added', ?, ?)",
            UUID.randomUUID(),
            siteId,
            at,
            at,
        )
        return siteId
    }

    private fun scheduledScanCount(siteId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM scans WHERE site_id = ? AND trigger_source = 'scheduled'",
            Int::class.java,
            siteId,
        ) ?: 0
}
