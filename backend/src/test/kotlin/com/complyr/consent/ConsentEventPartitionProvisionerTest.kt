package com.complyr.consent

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consent_events partition provisioner against real Postgres. This is a GDPR-safety job, not a
 * convenience: the V4 append-only trigger means any row that lands in the un-partitioned DEFAULT
 * safety-net can never be aged out by DROP PARTITION nor DELETEd, so it would breach GDPR
 * storage-limitation (Art. 5(1)(e), ARCHITECTURE.md §5). These lock the guarantees: monthly partitions
 * are kept provisioned ahead of the write path, a non-empty DEFAULT is detected, and — crucially — a
 * single un-reclaimable row cannot wedge the whole job.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
// Pin a small horizon so the assertions are deterministic regardless of the prod default.
// The literal MUST match [LOOKAHEAD] below (annotation args can't reference the const).
@TestPropertySource(properties = ["complyr.consent.partition-lookahead-months=2"])
class ConsentEventPartitionProvisionerTest {
    @Autowired
    private lateinit var provisioner: ConsentEventPartitionProvisioner

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `provisions the current month through the lookahead horizon`() {
        provisioner.provision()

        val current = YearMonth.now(ZoneOffset.UTC)
        (0..LOOKAHEAD).forEach { offset ->
            val name = partitionName(current.plusMonths(offset.toLong()))
            assertTrue(partitionExists(name), "$name must exist after provisioning")
        }
    }

    @Test
    fun `recreates a missing future partition and reports it`() {
        val target = YearMonth.now(ZoneOffset.UTC).plusMonths(LOOKAHEAD.toLong())
        val name = partitionName(target)
        // Detach+drop the (empty) partition so the run has something to create deterministically.
        jdbcTemplate.execute("DROP TABLE IF EXISTS $name")
        assertFalse(partitionExists(name), "precondition: the partition is gone")

        val result = provisioner.provision()

        assertTrue(partitionExists(name), "the missing partition is recreated")
        assertTrue(name in result.createdPartitions, "the recreated partition is reported")
    }

    @Test
    fun `is idempotent so a second run creates nothing`() {
        provisioner.provision()
        val result = provisioner.provision()
        assertTrue(result.createdPartitions.isEmpty(), "all target months already exist, so nothing is created")
    }

    @Test
    fun `detects and reports rows that fell into the DEFAULT partition`() {
        // A consent event dated far beyond the provisioned horizon has no monthly partition, so it lands
        // in DEFAULT — exactly the un-reclaimable state this job exists to catch.
        val userId = UUID.randomUUID()
        seedDefaultPartitionRow(userId, createdAt = OffsetDateTime.of(2035, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC))
        try {
            val result = provisioner.provision()
            assertTrue(result.defaultPartitionNonEmpty, "a row in DEFAULT must be detected and reported")
        } finally {
            // TRUNCATE targets only this partition and fires no DELETE trigger, so the append-only
            // guard is respected while the shared container is left clean for other tests.
            jdbcTemplate.execute("TRUNCATE consent_events_default")
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId) // sites cascade
        }
    }

    @Test
    fun `one poisoned DEFAULT row does not wedge provisioning of the other months`() {
        // Worst case: a consent row for a month INSIDE the lookahead landed in DEFAULT while that
        // month's partition was briefly missing. Recreating just that month now errors on the
        // partition-constraint scan — but because each month is its own transaction, that failure must
        // neither sink the sibling months nor suppress the DEFAULT drift alert. A single-transaction
        // design would roll back the whole run and, since V4 makes the row un-deletable, wedge forever.
        val current = YearMonth.now(ZoneOffset.UTC)
        val poisonedMonth = current.plusMonths(1)
        val poisonedName = partitionName(poisonedMonth)
        val userId = UUID.randomUUID()

        // Drop the poisoned month's partition, then insert a row in its range so the row lands in DEFAULT.
        jdbcTemplate.execute("DROP TABLE IF EXISTS $poisonedName")
        seedDefaultPartitionRow(userId, createdAt = poisonedMonth.atDay(15).atTime(12, 0).atOffset(ZoneOffset.UTC))
        try {
            val result = provisioner.provision()

            assertTrue(result.defaultPartitionNonEmpty, "the DEFAULT drift alert must still fire")
            assertFalse(partitionExists(poisonedName), "the poisoned month cannot be created until remediated")
            assertTrue(partitionExists(partitionName(current)), "the current month is provisioned regardless")
            assertTrue(
                partitionExists(partitionName(current.plusMonths(LOOKAHEAD.toLong()))),
                "the horizon month is provisioned regardless",
            )
        } finally {
            jdbcTemplate.execute("TRUNCATE consent_events_default")
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId) // sites cascade
            // DEFAULT is clean again, so restore the partition we dropped for the shared container.
            provisioner.provision()
        }
    }

    private fun seedDefaultPartitionRow(
        userId: UUID,
        createdAt: OffsetDateTime,
    ) {
        val siteId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')",
            userId,
            "part-$userId@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO sites (id, user_id, domain, site_key) VALUES (?, ?, ?, ?)",
            siteId,
            userId,
            "part-$siteId.com",
            "key-$siteId",
        )
        jdbcTemplate.update(
            "INSERT INTO consent_events (id, site_id, visitor_id, action, categories_jsonb, created_at) " +
                "VALUES (?, ?, ?, 'accept_all', '{}'::jsonb, ?)",
            UUID.randomUUID(),
            siteId,
            UUID.randomUUID(),
            createdAt,
        )
    }

    private fun partitionExists(name: String): Boolean =
        jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, name) ?: false

    private fun partitionName(month: YearMonth): String = "consent_events_%04d_%02d".format(month.year, month.monthValue)

    private companion object {
        const val LOOKAHEAD = 2
    }
}
