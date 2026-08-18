package eu.cookiekeeper.consent

import eu.cookiekeeper.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consent_events retention reaper (ADR-16) against real Postgres. It drops whole monthly partitions
 * once their entire month has aged past the retention window — the only sanctioned way to erase consent
 * evidence, since V4 makes rows un-DELETE-able and GDPR storage-limitation (Art. 5(1)(e)) requires the
 * trail to eventually go. These lock the guarantees: old partitions are dropped, boundary months are
 * NEVER dropped early (irreversible — the off-by-one must fail safe), the age-blind DEFAULT is never
 * touched, and a re-run is idempotent.
 *
 * A deliberately huge retention window is pinned so every partition this test creates sits far in the
 * historical past (well before the V3 migration / provisioner months) — the reaper only ever considers
 * those, so the shared container's live partitions are never disturbed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
// 20 years — pins every created month far before the V3/provisioner range so the reaper never touches a
// live partition. The literal MUST match [RETENTION_MONTHS] below (annotation args can't reference the
// const); it exceeds the app default (36) and stays above the MIN_RETENTION_MONTHS floor (36).
@TestPropertySource(properties = ["complyr.consent.retention-months=240"])
class ConsentEventPartitionReaperTest {
    @Autowired
    private lateinit var reaper: ConsentEventPartitionReaper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    // Anchored to now − retention so the created months are historical regardless of when CI runs.
    private val oldestKept = YearMonth.now(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS)
    private val justDroppable = oldestKept.minusMonths(1)
    private val olderDroppable = oldestKept.minusMonths(2)

    // A pattern-adjacent but INVALID-month child (MM=13) far in the historical past. The reaper must skip
    // it, not throw on YearMonth.of(y, 13) (see the odd-name test); named literally since partitionName
    // only formats real months.
    private val oddMonthChild = "consent_events_%04d_13".format(olderDroppable.year)

    @AfterEach
    fun cleanup() {
        // Empty historical partitions only — leave the shared container's live months intact.
        (listOf(oldestKept, justDroppable, olderDroppable).map(::partitionName) + oddMonthChild).forEach {
            jdbcTemplate.execute("DROP TABLE IF EXISTS $it")
        }
    }

    @Test
    fun `drops partitions whose whole month is past the retention window`() {
        createEmptyPartition(justDroppable)
        createEmptyPartition(olderDroppable)

        val result = reaper.reap()

        listOf(justDroppable, olderDroppable).forEach {
            val name = partitionName(it)
            assertFalse(partitionExists(name), "$name is past retention and must be dropped")
            assertTrue(name in result.droppedPartitions, "$name must be reported as dropped")
        }
    }

    @Test
    fun `keeps the partition exactly at the retention boundary`() {
        // The month == now − retention holds rows as young as exactly the window, so it must survive:
        // dropping it would erase evidence still within retention (the irreversible off-by-one).
        createEmptyPartition(oldestKept)
        val name = partitionName(oldestKept)

        val result = reaper.reap()

        assertTrue(partitionExists(name), "$name is exactly at the boundary and must be kept")
        assertFalse(name in result.droppedPartitions, "$name must not be reported as dropped")
    }

    @Test
    fun `never drops the age-blind DEFAULT partition`() {
        reaper.reap()

        assertTrue(partitionExists("consent_events_default"), "the DEFAULT partition must never be dropped by retention")
    }

    @Test
    fun `keeps the current month partition`() {
        // Provisioned at startup; a retention run must not over-drop recent, in-window data.
        val current = partitionName(YearMonth.now(ZoneOffset.UTC))
        assertTrue(partitionExists(current), "precondition: the current month is provisioned")

        val result = reaper.reap()

        assertTrue(partitionExists(current), "the current month is within retention and must be kept")
        assertFalse(current in result.droppedPartitions, "the current month must not be reported as dropped")
    }

    @Test
    fun `skips a pattern-adjacent child with an invalid month instead of throwing out of the run`() {
        // An odd child whose name matches consent_events_\d{4}_\d{2} but has month 13 must not make
        // YearMonth.of throw out of the whole run (which would silently wedge retention) — it is skipped
        // like the DEFAULT partition, while a genuinely droppable sibling in the same run is still dropped.
        createChildPartition(oddMonthChild, monthStart(olderDroppable), monthStart(olderDroppable.plusMonths(1)))
        createEmptyPartition(justDroppable)

        val result = reaper.reap()

        assertTrue(partitionExists(oddMonthChild), "the invalid-month child must be skipped, not dropped")
        assertFalse(oddMonthChild in result.droppedPartitions, "the invalid-month child must not be reported as dropped")
        assertFalse(partitionExists(partitionName(justDroppable)), "the valid droppable sibling must still be dropped")
    }

    @Test
    fun `is idempotent so a second run drops nothing new`() {
        createEmptyPartition(justDroppable)
        val first = reaper.reap()
        assertTrue(partitionName(justDroppable) in first.droppedPartitions, "precondition: the first run drops it")

        val second = reaper.reap()

        assertTrue(second.droppedPartitions.isEmpty(), "the partition is already gone, so a re-run drops nothing")
    }

    private fun createEmptyPartition(month: YearMonth) =
        createChildPartition(partitionName(month), monthStart(month), monthStart(month.plusMonths(1)))

    // Attaches a child under [name] with the given half-open bounds. The name need not match the bounds
    // (the odd-month test relies on that) — the reaper decides purely from the child's name.
    private fun createChildPartition(
        name: String,
        from: String,
        to: String,
    ) {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS $name PARTITION OF consent_events " +
                "FOR VALUES FROM (TIMESTAMPTZ '$from') TO (TIMESTAMPTZ '$to')",
        )
    }

    private fun partitionExists(name: String): Boolean =
        jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, name) ?: false

    private fun partitionName(month: YearMonth): String = "consent_events_%04d_%02d".format(month.year, month.monthValue)

    private fun monthStart(month: YearMonth): String = "%04d-%02d-01 00:00:00+00".format(month.year, month.monthValue)

    private companion object {
        // MUST match the @TestPropertySource literal above.
        const val RETENTION_MONTHS = 240L
    }
}
