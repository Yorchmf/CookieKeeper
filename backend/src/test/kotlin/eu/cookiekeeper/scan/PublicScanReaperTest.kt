package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The retention reaper against real Postgres: it must purge public scans past their TTL horizon and
 * leave fresh ones. Over-eager pruning would delete a result the visitor can still open with their
 * token (within the 7-day window); under-pruning would retain visitor domains + lead emails past the
 * stated window, which for a GDPR product is the failure that matters most.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
// Pin a tiny batch size so a handful of rows forces the multi-batch prune loop (prod default 500).
@TestPropertySource(properties = ["cookiekeeper.scan.public-scan-prune-batch-size=2"])
class PublicScanReaperTest {
    @Autowired
    private lateinit var reaper: PublicScanReaper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `prune deletes scans past the TTL horizon and keeps fresh ones`() {
        val expired = insertScan(expiresAt = Instant.now().minus(Duration.ofDays(1)))
        val fresh = insertScan(expiresAt = Instant.now().plus(Duration.ofDays(7)))

        reaper.prune()

        assertEquals(0, countScan(expired), "a scan past its expires_at horizon is pruned")
        assertEquals(1, countScan(fresh), "a scan still inside its 7-day window survives so the token can open it")
    }

    @Test
    fun `prune cascades to the scan's cookies`() {
        // The FK is ON DELETE CASCADE, so deleting an expired scan must also remove its observations;
        // guards against the cascade being dropped and orphan cookie rows accumulating.
        val expired = insertScan(expiresAt = Instant.now().minus(Duration.ofDays(1)))
        insertCookie(expired, name = "_ga")
        insertCookie(expired, name = "_fbp")

        reaper.prune()

        assertEquals(0, countScan(expired), "the expired scan is pruned")
        assertEquals(0, countCookies(expired), "its cookies cascade-delete with it, leaving no orphans")
    }

    @Test
    fun `prune drains a backlog spanning multiple batches`() {
        // Batch size is pinned to 2 for this class, so 5 expired scans force three batches (2+2+1);
        // a single-shot LIMIT delete would leave three rows behind.
        val expired = (1..5).map { insertScan(expiresAt = Instant.now().minus(Duration.ofDays(1))) }
        val fresh = insertScan(expiresAt = Instant.now().plus(Duration.ofDays(7)))

        reaper.prune()

        expired.forEach {
            assertEquals(0, countScan(it), "every expired scan is removed even when it takes several batches")
        }
        assertEquals(1, countScan(fresh), "a fresh scan is never selected by the batched delete")
    }

    @Test
    fun `migration applies churn-tuned autovacuum storage params to both scan tables`() {
        // V10 tightens autovacuum for the reaper's insert-right / delete-left churn — on public_scans
        // (amplified by per-scan UPDATEs) and on public_scan_cookies (up to ~250k cascade-deleted rows
        // per prune batch). Guards against the migration being dropped or its param names drifting.
        val expected =
            listOf(
                "autovacuum_vacuum_scale_factor=0.02",
                "autovacuum_vacuum_threshold=500",
                "autovacuum_vacuum_insert_scale_factor=0.05",
                "autovacuum_vacuum_insert_threshold=500",
                "autovacuum_analyze_scale_factor=0.02",
            )
        listOf("public_scans", "public_scan_cookies").forEach { table ->
            val reloptions =
                jdbcTemplate.queryForObject(
                    "SELECT array_to_string(reloptions, ',') FROM pg_class WHERE relname = ? AND relkind = 'r'",
                    String::class.java,
                    table,
                )
            assertNotNull(reloptions, "$table must carry table-level autovacuum params")
            expected.forEach { param ->
                assertTrue(reloptions.contains(param), "V10 must set $param on $table — got: $reloptions")
            }
        }
    }

    @Test
    fun `prune is skipped while another instance holds the advisory lock`() {
        val expired = insertScan(expiresAt = Instant.now().minus(Duration.ofDays(1)))

        // Simulate a second replica already pruning: hold the same advisory lock on a separate
        // session, then the reaper must no-op instead of deleting under it.
        dataSource.connection.use { holder ->
            holder.autoCommit = true
            advisoryLock(holder, "pg_advisory_lock")
            try {
                reaper.prune()
                assertEquals(
                    1,
                    countScan(expired),
                    "prune must skip while another instance holds the lock, leaving the expired scan untouched",
                )
            } finally {
                advisoryLock(holder, "pg_advisory_unlock")
            }
        }

        // With the lock released, the next run prunes the same scan as normal.
        reaper.prune()
        assertEquals(0, countScan(expired), "once the advisory lock is free the expired scan is pruned")
    }

    /** Session-level lock/unlock on a dedicated connection, keyed the same as the reaper's guard. */
    private fun advisoryLock(
        connection: Connection,
        function: String,
    ) {
        connection.prepareStatement("SELECT $function(?)").use { statement ->
            statement.setLong(1, PublicScanReaper.ADVISORY_LOCK_KEY)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    /**
     * Insert a public scan expiring at [expiresAt], returning its id. `created_at` is pinned safely
     * before [expiresAt] to satisfy the `ck_public_scans_expires_after_created` CHECK even for rows
     * whose horizon is already in the past.
     */
    private fun insertScan(expiresAt: Instant): UUID {
        val id = UUID.randomUUID()
        val createdAt = expiresAt.minus(Duration.ofDays(7))
        jdbcTemplate.update(
            "INSERT INTO public_scans (id, domain, status, public_token, created_at, updated_at, expires_at) " +
                "VALUES (?, ?, 'done', ?, ?, ?, ?)",
            id,
            "example-${id.toString().take(8)}.com",
            // The token is UNIQUE; derive it from the id so parallel inserts never collide.
            "tok-$id",
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
            OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
        )
        return id
    }

    private fun insertCookie(
        scanId: UUID,
        name: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO public_scan_cookies (id, public_scan_id, name) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            scanId,
            name,
        )
    }

    private fun countScan(id: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public_scans WHERE id = ?",
            Int::class.java,
            id,
        ) ?: 0

    private fun countCookies(scanId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public_scan_cookies WHERE public_scan_id = ?",
            Int::class.java,
            scanId,
        ) ?: 0
}
