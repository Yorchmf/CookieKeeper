package com.complyr.consent

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The reaper against real Postgres: it must delete keys past the retention window and leave
 * fresh ones — an over-eager prune would drop a key whose widget retry is still in flight,
 * re-opening the duplicate-audit-row hole the dedupe gate exists to close.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ConsentIdempotencyReaperTest {
    @Autowired
    private lateinit var reaper: ConsentIdempotencyReaper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

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
