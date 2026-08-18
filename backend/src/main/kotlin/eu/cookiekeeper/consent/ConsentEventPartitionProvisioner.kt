package eu.cookiekeeper.consent

import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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
 * Keeps `consent_events` monthly RANGE partitions provisioned ahead of the write path, and alerts if
 * the DEFAULT safety-net partition ever holds rows. This is a GDPR-safety job, not housekeeping: with
 * the V4 append-only trigger in place, a row that lands in DEFAULT can be removed only by dropping the
 * whole DEFAULT partition (age-blind) — it can neither be aged out by monthly `DROP PARTITION` nor
 * `DELETE`d, so it would breach GDPR storage-limitation (Art. 5(1)(e)). See ARCHITECTURE.md §5.
 *
 * Runs nightly (off-peak) AND once at startup, so a fresh deploy immediately has its partitions even
 * if the next cron is hours away. It pre-creates the current month plus
 * [CookieKeeperProperties.Consent.partitionLookaheadMonths] future months; the wide buffer means even a
 * multi-week outage of this job never lets a row fall into DEFAULT.
 *
 * Resilience is deliberate and hard-won (see the DDL notes below):
 *  - **DEFAULT drift is checked FIRST and unconditionally**, in a plain read outside any lock, so the
 *    alert always fires even if a partition creation later fails.
 *  - **Each month is provisioned in its OWN transaction.** A single poisoned DEFAULT row (one whose
 *    month falls inside the lookahead) makes `CREATE TABLE ... PARTITION OF` for that month error on
 *    the partition-constraint scan; a one-transaction run would roll back *every* month and, because
 *    V4 makes the DEFAULT row un-deletable, wedge the job permanently. Per-month transactions contain
 *    that blast radius — the other months still provision and the drift alert still fires.
 *  - **`SET LOCAL lock_timeout`** bounds the ACCESS EXCLUSIVE lock `CREATE ... PARTITION OF` takes on
 *    the parent: a contended create fails fast and retries next run instead of queuing ahead of — and
 *    freezing — the hot consent-INSERT path.
 *
 * Multi-instance safe: the entrypoints fire on every replica, so each month's transaction claims a
 * transaction-scoped advisory lock and no-ops if another instance already holds it. (See
 * [eu.cookiekeeper.common.SchedulingConfig] for why any shared-state `@Scheduled` job must leader-guard.)
 *
 * Retention (dropping partitions past the retention window) is intentionally NOT done here — it is a
 * separate, higher-risk concern that irreversibly deletes audit evidence and warrants its own job.
 */
@Component
class ConsentEventPartitionProvisioner(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ConsentEventPartitionProvisioner::class.java)

    // One short transaction PER partition (not one spanning the whole run), so the advisory lock and
    // the parent's ACCESS EXCLUSIVE lock are scoped to a single CREATE and released at each commit.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Nightly cron entrypoint. Overridable via `cookiekeeper.consent.partition-provision-cron` (defaulted
     * here so no yml entry is required, and offset ahead of the reapers so it never waits on them).
     */
    @Scheduled(cron = "\${cookiekeeper.consent.partition-provision-cron:$DEFAULT_PROVISION_CRON}")
    fun scheduledProvision() {
        provision()
    }

    /**
     * Startup entrypoint. A freshly deployed instance must have its partitions before it serves the
     * first consent write, not only after the first nightly cron. Wrapped so a transient DB error at
     * boot only logs and defers to the cron — an unhandled throw from an `ApplicationReadyEvent`
     * listener would fail application startup and boot-loop the deploy. Returns nothing so Spring
     * never mistakes a return value for a published event.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun provisionOnStartup() {
        try {
            provision()
        } catch (ex: DataAccessException) {
            log.error(
                "Startup consent_events partition provisioning failed; deferring to the nightly run: {}",
                ex.message,
            )
        }
    }

    /**
     * Checks DEFAULT drift, then ensures the current + lookahead months exist. Public and returning a
     * [ProvisionResult] so tests can assert without scraping logs; the scheduled/startup entrypoints
     * discard the result. The DEFAULT probe is intentionally the first thing done and runs whatever
     * happens to the per-month creations below.
     */
    fun provision(): ProvisionResult {
        val defaultNonEmpty = isDefaultPartitionNonEmpty()
        val current = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC))
        val created =
            (0..properties.consent.partitionLookaheadMonths)
                .map { current.plusMonths(it.toLong()) }
                .filter { ensurePartition(it) }
                .map(::partitionName)
        val result = ProvisionResult(created, defaultNonEmpty)
        logResult(result)
        return result
    }

    /**
     * True if the DEFAULT partition holds any row. `EXISTS` (not `count(*)`) so the check stays cheap
     * exactly in the degraded case — a non-empty DEFAULT — where a full count would be a seq scan. Runs
     * in autocommit outside any lock, taking only an ACCESS SHARE on the DEFAULT partition, never the
     * parent, so it can never block the consent-INSERT path.
     */
    private fun isDefaultPartitionNonEmpty(): Boolean =
        jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM $DEFAULT_PARTITION)", Boolean::class.java) ?: false

    /**
     * Creates the monthly partition for [month] if absent, in its own transaction; returns true only
     * when it created one. The name and bounds are derived from integers (year/month), never user
     * input, and the name is re-validated against [PARTITION_NAME_PATTERN] before it is interpolated
     * into DDL — so this unavoidable string-built DDL carries no injection surface. Bounds are pinned
     * to UTC midnight to match the V3 migration's convention exactly.
     *
     * A failure here (lock timeout under contention, or a poisoned DEFAULT row whose range is this
     * month) is logged and swallowed so it neither sinks the sibling months nor wedges the whole job;
     * Postgres DDL errors carry the constraint name, not row data, so the message is PII-safe to log.
     */
    @Suppress("SwallowedException")
    private fun ensurePartition(month: YearMonth): Boolean {
        val name = partitionName(month)
        require(PARTITION_NAME_PATTERN.matches(name)) { "refusing to create partition with unexpected name: $name" }
        return try {
            // execute() returns a platform Boolean!; `== true` treats a null (empty transaction) as
            // "not created" without the redundant-elvis warning the compiler emits on `?: false`.
            transactionTemplate.execute { createPartitionIfLeaderAndAbsent(name, month) } == true
        } catch (ex: DataAccessException) {
            log.error("Failed to provision consent_events partition {}: {}", name, ex.message)
            false
        }
    }

    private fun createPartitionIfLeaderAndAbsent(
        name: String,
        month: YearMonth,
    ): Boolean {
        // Fail fast instead of freezing the consent-INSERT path: bound how long CREATE's ACCESS
        // EXCLUSIVE request on the parent may queue, and cap the whole statement as a backstop.
        jdbcTemplate.execute("SET LOCAL lock_timeout = '$LOCK_TIMEOUT'")
        jdbcTemplate.execute("SET LOCAL statement_timeout = '$STATEMENT_TIMEOUT'")
        return when {
            // Serialize the check-then-create across replicas so a race can't error out of IF NOT EXISTS.
            !tryLeaderLock() -> false
            partitionExists(name) -> false
            else -> {
                val next = month.plusMonths(1)
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS $name PARTITION OF consent_events " +
                        "FOR VALUES FROM (TIMESTAMPTZ '${monthStart(month)}') TO (TIMESTAMPTZ '${monthStart(next)}')",
                )
                true
            }
        }
    }

    private fun tryLeaderLock(): Boolean =
        jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean::class.java, ADVISORY_LOCK_KEY)
            ?: false

    private fun partitionExists(name: String): Boolean =
        jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, name) ?: false

    private fun logResult(result: ProvisionResult) {
        if (result.createdPartitions.isNotEmpty()) {
            log.info("Pre-created consent_events partition(s): {}", result.createdPartitions.joinToString(", "))
        }
        if (result.defaultPartitionNonEmpty) {
            log.error(
                "consent_events DEFAULT partition is NON-EMPTY: those rows cannot be aged out by DROP " +
                    "PARTITION and breach GDPR storage-limitation (Art. 5(1)(e)) — manual remediation " +
                    "required (see ARCHITECTURE.md §5).",
            )
        }
    }

    private fun partitionName(month: YearMonth): String = "consent_events_%04d_%02d".format(month.year, month.monthValue)

    private fun monthStart(month: YearMonth): String = "%04d-%02d-01 00:00:00+00".format(month.year, month.monthValue)

    /** Structured result of a provisioning run — created partition names + whether DEFAULT holds rows. */
    data class ProvisionResult(
        val createdPartitions: List<String>,
        val defaultPartitionNonEmpty: Boolean,
    )

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes each
         * partition creation across instances. Kept distinct from every other `pg_advisory*` key the
         * app takes (see [ConsentIdempotencyReaper], [eu.cookiekeeper.scan.PublicScanReaper]).
         */
        internal const val ADVISORY_LOCK_KEY: Long = 6_401_558_237L

        private const val DEFAULT_PARTITION = "consent_events_default"

        // Guards the only string-built DDL in this class: a name that isn't exactly
        // consent_events_YYYY_MM is refused before it can reach `CREATE TABLE`.
        private val PARTITION_NAME_PATTERN = Regex("^consent_events_\\d{4}_\\d{2}$")

        // Short so a contended CREATE aborts fast rather than head-of-line-blocking consent INSERTs;
        // the statement cap is a wider backstop for the (empty) DEFAULT scan CREATE does internally.
        private const val LOCK_TIMEOUT = "3s"
        private const val STATEMENT_TIMEOUT = "15s"

        // 03:15 daily (server zone) — off the traffic peak and ahead of the consent-idempotency (03:30)
        // and public-scan (03:45) reapers, so provisioning never queues behind their sweeps.
        private const val DEFAULT_PROVISION_CRON = "0 15 3 * * *"
    }
}
