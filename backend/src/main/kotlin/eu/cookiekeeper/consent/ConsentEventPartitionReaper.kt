package eu.cookiekeeper.consent

import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Time-based retention for `consent_events` (ADR-16): drops whole monthly RANGE partitions once their
 * entire month has aged past [CookieKeeperProperties.Consent.retentionMonths]. This is the other half of the
 * partition lifecycle owned by [ConsentEventPartitionProvisioner] — the provisioner creates months
 * ahead of the write path, this reaper drops them behind the retention horizon.
 *
 * `DROP PARTITION` is the ONLY sanctioned way to remove consent evidence: the V4 append-only trigger
 * makes rows un-`DELETE`-able, and GDPR storage-limitation (Art. 5(1)(e)) requires us to delete the
 * audit trail once it is no longer needed. A drop is an instant metadata operation that never touches
 * a live row.
 *
 * Correctness — a partition is dropped ONLY when no row it could hold is still within retention:
 *  - Each monthly partition `consent_events_YYYY_MM` covers `[month-01, next-month-01)`. We drop it
 *    iff `month < currentMonth − retentionMonths`, so the newest-kept partition's oldest possible row
 *    is exactly `retentionMonths` old. This over-retains by up to one partition (≤ ~1 month) and NEVER
 *    under-retains — the safe direction for irreversible deletion of audit evidence.
 *  - The window is TENANT-BLIND (a partition mixes every tenant's rows for that month) and set to the
 *    LONGEST plan retention, so no customer loses evidence they are entitled to. Shorter per-plan
 *    windows are a read-layer product limit, not a physical-deletion promise (ADR-16).
 *  - `consent_events_default` is NEVER dropped here — it is age-blind (holds rows of arbitrary dates),
 *    and the provisioner's non-empty-DEFAULT alert owns that failure mode. Only children whose name
 *    matches `consent_events_YYYY_MM` are considered.
 *
 * Resilience mirrors the provisioner deliberately:
 *  - **Partitions are discovered from the catalog** (`pg_inherits`), not guessed, so history of unknown
 *    depth and any gaps are handled; each candidate name is re-validated against [PARTITION_NAME_PATTERN]
 *    before it is interpolated into `DROP TABLE`, so this string-built DDL carries no injection surface.
 *  - **Each partition is dropped in its OWN transaction** under a transaction-scoped advisory lock, so
 *    one failing drop neither rolls back its siblings nor strands the lock, and vacuum can run between.
 *  - **`SET LOCAL lock_timeout`** bounds the brief `ACCESS EXCLUSIVE` a `DROP` of a child takes on the
 *    parent: a contended drop fails fast and retries next run instead of head-of-line-blocking the hot
 *    consent-INSERT path.
 *
 * Cron-only, NO startup run: unlike provisioning (which must precede the first write), dropping is not
 * time-critical, and irreversible DDL must never fire during application boot. Multi-instance safe via
 * the per-drop advisory lock (see [eu.cookiekeeper.common.SchedulingConfig]).
 */
@Component
class ConsentEventPartitionReaper(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ConsentEventPartitionReaper::class.java)

    // One short transaction PER drop (not one spanning the whole run), so the advisory lock and the
    // parent's ACCESS EXCLUSIVE lock are scoped to a single DROP and released at each commit.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Daily cron entrypoint. Overridable via `cookiekeeper.consent.retention-drop-cron` (defaulted here so
     * no yml entry is required, and offset after the provisioner + reapers so it never contends with them).
     */
    @Scheduled(cron = "\${complyr.consent.retention-drop-cron:$DEFAULT_RETENTION_DROP_CRON}")
    fun scheduledReap() {
        reap()
    }

    /**
     * Drops every monthly partition older than the retention horizon. Public and returning a
     * [RetentionResult] so tests can assert on the dropped names without scraping logs; the scheduled
     * entrypoint discards the result.
     */
    fun reap(): RetentionResult {
        val oldestKept = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).minusMonths(properties.consent.retentionMonths.toLong())
        val dropped =
            droppablePartitions(oldestKept)
                .filter { dropPartitionIfLeader(it) }
        logResult(dropped, oldestKept)
        warnIfOverdueRemain(oldestKept)
        return RetentionResult(dropped)
    }

    /**
     * The attached monthly partitions whose entire month falls before [oldestKept], oldest first.
     * Discovered from the catalog and filtered to the `consent_events_YYYY_MM` convention, which
     * excludes `consent_events_default` and any non-conforming child. Ordering is cosmetic (stable,
     * chronological logs) — each drop is independent.
     */
    private fun droppablePartitions(oldestKept: YearMonth): List<String> =
        listMonthlyPartitions()
            .mapNotNull { name -> parseMonth(name)?.let { month -> name to month } }
            .filter { (_, month) -> month.isBefore(oldestKept) }
            .sortedBy { (_, month) -> month }
            .map { (name, _) -> name }

    /**
     * All child tables of the `consent_events` partitioned parent, by relname. Read outside any lock
     * (plain catalog query); the age filter and the name-pattern guard are applied in [droppablePartitions].
     */
    private fun listMonthlyPartitions(): List<String> {
        val names =
            jdbcTemplate.queryForList(
                """
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """.trimIndent(),
                String::class.java,
                PARENT_TABLE,
            )
        // queryForList returns a platform List<String!>; drop any nulls so the return type is non-null.
        return names.filterNotNull()
    }

    /**
     * Drops [name] in its own transaction if this instance wins the leader lock; returns true only when
     * it dropped one. The name is re-validated against [PARTITION_NAME_PATTERN] before it reaches DDL —
     * so this unavoidable string-built `DROP TABLE` carries no injection surface (the name comes from the
     * catalog, but the guard is defence-in-depth against a manually-created odd child). A failure (lock
     * timeout under contention) is logged and swallowed so it neither sinks the other drops nor wedges
     * the run; Postgres DDL errors carry the object name, not row data, so the message is PII-safe.
     */
    @Suppress("SwallowedException")
    private fun dropPartitionIfLeader(name: String): Boolean {
        require(PARTITION_NAME_PATTERN.matches(name)) { "refusing to drop partition with unexpected name: $name" }
        return try {
            transactionTemplate.execute { dropIfLeader(name) } == true
        } catch (ex: DataAccessException) {
            log.error("Failed to drop consent_events partition {}: {}", name, ex.message)
            false
        }
    }

    private fun dropIfLeader(name: String): Boolean {
        // Fail fast instead of freezing the consent-INSERT path: bound how long DROP's ACCESS EXCLUSIVE
        // request on the parent may queue, and cap the whole statement as a backstop.
        jdbcTemplate.execute("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'")
        jdbcTemplate.execute("SET LOCAL statement_timeout = '$STATEMENT_TIMEOUT'")
        return when {
            !tryLeaderLock() -> false
            else -> {
                jdbcTemplate.execute("DROP TABLE IF EXISTS $name")
                true
            }
        }
    }

    private fun tryLeaderLock(): Boolean =
        jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, ADVISORY_LOCK_KEY)
            ?: false

    private fun logResult(
        dropped: List<String>,
        oldestKept: YearMonth,
    ) {
        if (dropped.isNotEmpty()) {
            log.info(
                "Dropped {} consent_events partition(s) past the {}-month retention window (keeping {} onward): {}",
                dropped.size,
                properties.consent.retentionMonths,
                oldestKept,
                dropped.joinToString(", "),
            )
        }
    }

    /**
     * Guards against a SILENT retention failure: after the run, any partition still attached whose whole
     * month is past the horizon is overdue — it should have been dropped this run (or a prior one). A
     * stuck advisory lock, repeated lock/statement timeouts, or a privilege regression would otherwise
     * make a failing run indistinguishable from a healthy "nothing to drop" night, letting evidence pile
     * up past retention with no operator signal — a GDPR storage-limitation blind spot.
     *
     * We RE-READ the catalog rather than trust this instance's drop tally: a non-leader replica
     * legitimately drops nothing, and [dropPartitionIfLeader] can't tell "lost the leader race" (benign)
     * from "the drop errored" (concerning). Whether a partition is STILL past the horizon after the run
     * is the true, instance-agnostic breach condition. (A rare benign over-warn is possible if a peer
     * replica is mid-drop of the same partition at this instant; it self-clears on the next run.)
     */
    private fun warnIfOverdueRemain(oldestKept: YearMonth) {
        val overdue = droppablePartitions(oldestKept)
        if (overdue.isNotEmpty()) {
            log.warn(
                "{} consent_events partition(s) are past the {}-month retention window but remain attached " +
                    "after this run (lock contention, timeout, or a privilege issue) — retention is not " +
                    "keeping up; investigate if this persists: {}",
                overdue.size,
                properties.consent.retentionMonths,
                overdue.joinToString(", "),
            )
        }
    }

    /** True only for a name of the form `consent_events_YYYY_MM`; anything else (e.g. DEFAULT) yields null. */
    private fun parseMonth(name: String): YearMonth? {
        val match = PARTITION_NAME_PATTERN.matchEntire(name) ?: return null
        val (year, month) = match.destructured
        return YearMonth.of(year.toInt(), month.toInt())
    }

    /** Structured result of a retention run — the partition names dropped this run (empty if none). */
    data class RetentionResult(
        val droppedPartitions: List<String>,
    )

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes each
         * partition drop across instances. Kept distinct from every other `pg_advisory*` key the app
         * takes (provisioner, idempotency reaper, public-scan reaper, webhook reaper).
         */
        internal const val ADVISORY_LOCK_KEY: Long = 5_902_447_183L

        private const val PARENT_TABLE = "consent_events"

        // Captures YYYY and MM (MM constrained to 01–12) so the same guard both filters (excludes
        // _default / odd children) and parses the month; a name that isn't exactly consent_events_YYYY_MM
        // with a real month never reaches DROP TABLE. The 01–12 bound matters: a pattern-matching but
        // invalid month (e.g. _00, _13) would make YearMonth.of throw out of the whole run in
        // droppablePartitions (its try/catch only wraps the drop), silently wedging retention.
        private val PARTITION_NAME_PATTERN = Regex("^consent_events_(\\d{4})_(0[1-9]|1[0-2])$")

        // Short so a contended DROP aborts fast rather than head-of-line-blocking consent INSERTs; the
        // statement cap is a wider backstop (dropping an old, cold partition is otherwise near-instant).
        private const val LOCK_TIMEOUT = "3s"
        private const val STATEMENT_TIMEOUT = "15s"

        // 04:00 daily (server zone) — off the traffic peak and after the provisioner (03:15) and the
        // idempotency (03:30) / public-scan (03:45) reapers, so retention DDL never queues behind them.
        private const val DEFAULT_RETENTION_DROP_CRON = "0 0 4 * * *"
    }
}
