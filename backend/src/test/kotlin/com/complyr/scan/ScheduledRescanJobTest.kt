package com.complyr.scan

import com.complyr.TestcontainersConfiguration
import com.complyr.billing.Plan
import com.complyr.site.SiteStatus
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
 * The scheduled re-scan job against real Postgres. This is what makes every plan's
 * [com.complyr.billing.RescanFrequency] real, so the cases that matter are the due/skip decisions:
 * a site is re-scanned exactly when its plan cadence has elapsed, an Expired account is frozen, an
 * in-flight scan is never doubled, and a second run of the same night enqueues nothing more.
 *
 * The candidate query is GLOBAL (all active sites), and this Testcontainers DB is shared across test
 * classes that deliberately leave sites behind (see [ScanQueueTest]). Each test therefore archives
 * every pre-existing site up front so only its own freshly-seeded active sites are candidates, and
 * asserts on `trigger_source = 'scheduled'` rows per seeded site — the job's own output — rather than
 * on global counts. Pre-existing scans are seeded as `site_added` so they establish `last_scan_at`
 * without being mistaken for the job's work. The cron is disabled so only the explicit calls here run.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["complyr.scan.rescan-cron=-"])
class ScheduledRescanJobTest {
    @Autowired
    private lateinit var job: ScheduledRescanJob

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // The job commits its own transaction (enqueue writes jobs + scans), so there is no rollback
    // isolation. Clear the queue tables and archive every leftover site so this run starts from a
    // known, empty candidate set. Only jobs/scans are truncated — sites/users cascade into the
    // append-only consent_events table, which a DB guard trigger forbids (see ScanQueueTest).
    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE jobs, scans CASCADE")
        jdbcTemplate.update("UPDATE sites SET status = 'archived'")
    }

    @Test
    fun `a Starter site past its monthly cadence is enqueued`() {
        val site = seedSubscribedSite(Plan.STARTER, lastScanDaysAgo = 40)

        job.enqueueDueRescans()

        assertEquals(1, scheduledScanCount(site), "40d > 1 month, so the Starter site is due")
    }

    @Test
    fun `a Starter site inside its monthly cadence is left alone`() {
        // 10d passes the coarse 7-day SQL pre-filter but not the exact monthly cadence applied in Kotlin,
        // so this is precisely the case that proves plan logic lives in the job, not the query.
        val site = seedSubscribedSite(Plan.STARTER, lastScanDaysAgo = 10)

        job.enqueueDueRescans()

        assertEquals(0, scheduledScanCount(site), "10d < 1 month, so the Starter site is not yet due")
    }

    @Test
    fun `a Pro site past its weekly cadence is enqueued`() {
        val site = seedSubscribedSite(Plan.PRO, lastScanDaysAgo = 10)

        job.enqueueDueRescans()

        assertEquals(1, scheduledScanCount(site), "10d > 1 week, so the Pro site is due")
    }

    @Test
    fun `a never-scanned site is always due`() {
        val site = seedSubscribedSite(Plan.PRO, lastScanDaysAgo = null)

        job.enqueueDueRescans()

        assertEquals(1, scheduledScanCount(site), "a site that has never been scanned is due immediately")
    }

    @Test
    fun `an archived site is never re-scanned`() {
        val site = seedSubscribedSite(Plan.PRO, lastScanDaysAgo = 40, siteStatus = SiteStatus.ARCHIVED)

        job.enqueueDueRescans()

        assertEquals(0, scheduledScanCount(site), "an archived site is excluded by the status filter")
    }

    @Test
    fun `a site with a scan already in flight is not doubled`() {
        // Old enough to be due by timestamp, but a queued scan is already live — the NOT EXISTS guard must
        // win over the cadence check so the worker never gets two concurrent scans for one site.
        val site = seedSubscribedSite(Plan.PRO, lastScanDaysAgo = 40)
        insertScan(site, daysAgo = 1, status = ScanStatus.QUEUED, trigger = ScanTrigger.MANUAL)

        job.enqueueDueRescans()

        assertEquals(0, scheduledScanCount(site), "a live queued scan excludes the site via NOT EXISTS")
    }

    @Test
    fun `an Expired account is frozen`() {
        // No subscription and a user created well past the trial window resolves to Expired: the dashboard
        // is frozen (no new sites, no scans). Consent ingestion is untouched — that is a separate path.
        val userId = insertUser(createdAt = Instant.now().minus(Duration.ofDays(60)))
        val site = insertSite(userId)
        insertScan(site, daysAgo = 40, status = ScanStatus.DONE, trigger = ScanTrigger.SITE_ADDED)

        job.enqueueDueRescans()

        assertEquals(0, scheduledScanCount(site), "an Expired account is skipped even when due")
    }

    @Test
    fun `a Business site's scheduled scan is enqueued at high priority`() {
        // The batch resolves each owner's plan once (resolveAll) and stamps the claim priority from that
        // same resolution — a Business site's re-scan must carry the high tier without any per-site lookup.
        val site = seedSubscribedSite(Plan.BUSINESS, lastScanDaysAgo = 40)

        job.enqueueDueRescans()

        assertEquals(ScanQueue.PRIORITY_HIGH, scheduledScanPriority(site), "a Business re-scan claims at the high tier")
    }

    @Test
    fun `a Starter site's scheduled scan is enqueued at normal priority`() {
        val site = seedSubscribedSite(Plan.STARTER, lastScanDaysAgo = 40)

        job.enqueueDueRescans()

        assertEquals(ScanQueue.PRIORITY_NORMAL, scheduledScanPriority(site), "a Starter re-scan claims at the normal tier")
    }

    @Test
    fun `two runs of the same night enqueue exactly one scan`() {
        val site = seedSubscribedSite(Plan.PRO, lastScanDaysAgo = 40)

        job.enqueueDueRescans()
        job.enqueueDueRescans()

        assertEquals(
            1,
            scheduledScanCount(site),
            "the first run's queued scan trips NOT EXISTS for the second — the job is run-twice-safe",
        )
    }

    /** Insert an active-plan account with an active site, optionally with a prior scan [lastScanDaysAgo] old. */
    private fun seedSubscribedSite(
        plan: Plan,
        lastScanDaysAgo: Long?,
        siteStatus: SiteStatus = SiteStatus.ACTIVE,
    ): UUID {
        val userId = insertUser()
        insertSubscription(userId, plan)
        val siteId = insertSite(userId, siteStatus)
        if (lastScanDaysAgo != null) {
            insertScan(siteId, lastScanDaysAgo, ScanStatus.DONE, ScanTrigger.SITE_ADDED)
        }
        return siteId
    }

    private fun insertUser(createdAt: Instant = Instant.now()): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, 'x', ?)",
            id,
            "rescan-$id@example.com",
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        )
        return id
    }

    private fun insertSubscription(
        userId: UUID,
        plan: Plan,
    ) {
        jdbcTemplate.update(
            "INSERT INTO subscriptions (id, user_id, plan, status) VALUES (?, ?, ?, 'active')",
            UUID.randomUUID(),
            userId,
            plan.name,
        )
    }

    private fun insertSite(
        userId: UUID,
        status: SiteStatus = SiteStatus.ACTIVE,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO sites (id, user_id, domain, site_key, status) VALUES (?, ?, ?, ?, ?)",
            id,
            userId,
            "site-$id.example.com",
            "pk_$id",
            status.dbValue,
        )
        return id
    }

    private fun insertScan(
        siteId: UUID,
        daysAgo: Long,
        status: ScanStatus,
        trigger: ScanTrigger,
    ) {
        val at = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(daysAgo)), ZoneOffset.UTC)
        jdbcTemplate.update(
            "INSERT INTO scans (id, site_id, status, trigger_source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            siteId,
            status.dbValue,
            trigger.dbValue,
            at,
            at,
        )
    }

    private fun scheduledScanCount(siteId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM scans WHERE site_id = ? AND trigger_source = 'scheduled'",
            Int::class.java,
            siteId,
        ) ?: 0

    /** The claim priority stamped on the job backing this site's scheduled scan (joined via the payload). */
    private fun scheduledScanPriority(siteId: UUID): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT j.priority
            FROM jobs j
            JOIN scans s ON (j.payload_jsonb ->> 'scanId')::uuid = s.id
            WHERE s.site_id = ? AND s.trigger_source = 'scheduled'
            """.trimIndent(),
            Int::class.java,
            siteId,
        ) ?: error("no scheduled scan job for site $siteId")
}
