package eu.cookiekeeper.consent

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
 * The reaper against real Postgres: it must delete keys past the retention window and leave
 * fresh ones — an over-eager prune would drop a key whose widget retry is still in flight,
 * re-opening the duplicate-audit-row hole the dedupe gate exists to close.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
// Pin a tiny batch size so a handful of rows forces the multi-batch prune loop (prod default 10k).
@TestPropertySource(properties = ["cookiekeeper.consent.idempotency-prune-batch-size=2"])
class ConsentIdempotencyReaperTest {
    @Autowired
    private lateinit var reaper: ConsentIdempotencyReaper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `prune deletes keys past the retention window and keeps recent ones`() {
        val expired = UUID.randomUUID()
        val fresh = UUID.randomUUID()
        // 90 days is comfortably past any plausible (14d default) retention window; 1 hour is well inside it.
        insertKey(expired, Instant.now().minus(Duration.ofDays(90)))
        insertKey(fresh, Instant.now().minus(Duration.ofHours(1)))

        reaper.prune()

        assertEquals(0, countKey(expired), "a key older than the retention window is pruned")
        assertEquals(1, countKey(fresh), "a recently-claimed key survives so its in-flight retry still de-dupes")
    }

    @Test
    fun `migration applies churn-tuned autovacuum storage params`() {
        // V6 tightens autovacuum for this table's insert-right / delete-left churn (see the reaper).
        // Guards against the migration being dropped or its param names drifting.
        val reloptions =
            jdbcTemplate.queryForObject(
                "SELECT array_to_string(reloptions, ',') FROM pg_class " +
                    "WHERE relname = 'consent_idempotency' AND relkind = 'r'",
                String::class.java,
            )

        assertNotNull(reloptions, "consent_idempotency must carry table-level autovacuum params")
        val expected =
            listOf(
                "autovacuum_vacuum_scale_factor=0.02",
                "autovacuum_vacuum_threshold=500",
                "autovacuum_vacuum_insert_scale_factor=0.05",
                "autovacuum_vacuum_insert_threshold=500",
                "autovacuum_analyze_scale_factor=0.02",
            )
        expected.forEach { param ->
            assertTrue(reloptions.contains(param), "V6 must set $param — got: $reloptions")
        }
    }

    @Test
    fun `prune drains a backlog spanning multiple batches`() {
        // Batch size is pinned to 2 for this class, so 5 expired keys force three batches (2+2+1);
        // a single-shot LIMIT delete would leave three rows behind.
        val expired = (1..5).map { UUID.randomUUID() }
        expired.forEach { insertKey(it, Instant.now().minus(Duration.ofDays(90))) }
        val fresh = UUID.randomUUID()
        insertKey(fresh, Instant.now().minus(Duration.ofHours(1)))

        reaper.prune()

        expired.forEach {
            assertEquals(0, countKey(it), "every expired key is removed even when it takes several batches")
        }
        assertEquals(1, countKey(fresh), "a fresh key is never selected by the batched delete")
    }

    @Test
    fun `prune is skipped while another instance holds the advisory lock`() {
        val expired = UUID.randomUUID()
        insertKey(expired, Instant.now().minus(Duration.ofDays(90)))

        // Simulate a second replica already pruning: hold the same advisory lock on a separate
        // session, then the reaper must no-op instead of deleting under it.
        dataSource.connection.use { holder ->
            holder.autoCommit = true
            advisoryLock(holder, "pg_advisory_lock")
            try {
                reaper.prune()
                assertEquals(
                    1,
                    countKey(expired),
                    "prune must skip while another instance holds the lock, leaving the expired key untouched",
                )
            } finally {
                advisoryLock(holder, "pg_advisory_unlock")
            }
        }

        // With the lock released, the next run prunes the same key as normal.
        reaper.prune()
        assertEquals(0, countKey(expired), "once the advisory lock is free the expired key is pruned")
    }

    /** Session-level lock/unlock on a dedicated connection, keyed the same as the reaper's guard. */
    private fun advisoryLock(
        connection: Connection,
        function: String,
    ) {
        connection.prepareStatement("SELECT $function(?)").use { statement ->
            statement.setLong(1, ConsentIdempotencyReaper.ADVISORY_LOCK_KEY)
            statement.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun insertKey(
        key: UUID,
        claimedAt: Instant,
    ) {
        jdbcTemplate.update(
            "INSERT INTO consent_idempotency (event_key, created_at) VALUES (?, ?)",
            key,
            OffsetDateTime.ofInstant(claimedAt, ZoneOffset.UTC),
        )
    }

    private fun countKey(key: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM consent_idempotency WHERE event_key = ?",
            Int::class.java,
            key,
        ) ?: 0
}
