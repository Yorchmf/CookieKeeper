package com.complyr.scan

import com.complyr.billing.AccountEntitlement
import com.complyr.billing.EntitlementService
import com.complyr.common.ComplyrProperties
import com.complyr.site.RescanCandidate
import com.complyr.site.SiteRepository
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Makes each plan's [com.complyr.billing.RescanFrequency] real: nightly it enqueues a fresh scan for
 * every active site whose last scan is older than the owner's plan cadence (Starter monthly, Pro/Business
 * weekly). Without it a site would only ever be scanned once (at signup) plus whatever on-demand re-scans
 * a Pro/Business owner triggers — a Starter site would be stuck with a single, aging scan forever.
 *
 * **Verification is irrelevant here.** Slice 1 moved the verified-domain gate off the crawler: an
 * unverified site simply crawls in [com.complyr.scan.CrawlMode.QUICK] (identical posture to the anonymous
 * funnel), a verified one in FULL. So this job re-scans everyone; depth follows verification downstream.
 *
 * **Expired accounts are skipped**, matching the frozen-dashboard rule ("no new sites, no scans") in
 * [com.complyr.billing.EXPIRED_ENTITLEMENTS]. Note this does NOT touch consent ingestion — CLAUDE.md
 * constraint #3 protects the append-only consent log, not scanning, so freezing scans for a lapsed
 * account is legitimate billing pressure, not evidence loss.
 *
 * ## Transaction shape — read once, then commit per site
 *
 * The run is split into a short leader-guarded **read** and then one **independent transaction per
 * enqueue**, rather than a single transaction spanning the whole batch. That isolation is deliberate:
 * a batch-wide transaction means one site whose enqueue throws (a transient deadlock, a lock timeout)
 * rolls back *every* other site's enqueue too, so a single poison row silently freezes the entire
 * nightly re-scan fleet. Committing per site — the same per-unit-of-work commit [PublicScanReaper] uses,
 * and the same lock+recheck+enqueue the interactive path [ScanRequestService.request] uses — bounds the
 * blast radius of any one failure to that one site, which is simply retried next night.
 *
 * Two independent guards keep it safe to run repeatedly and across replicas:
 *  - **Correctness — per-site lock + `NOT EXISTS`:** each enqueue re-takes the site's advisory lock and
 *    re-checks for a live (queued/running) scan before inserting, exactly as [ScanRequestService] does.
 *    Combined with [SiteRepository.findRescanCandidates]'s own `NOT EXISTS` pre-filter (which excludes any
 *    site that already has a queued/running scan), a double-fire, an overlapping manual re-scan, or two
 *    replicas racing the same night can never double-enqueue a site. This is state-based, not
 *    timestamp-based, so it is robust to clock skew and missed runs.
 *  - **Efficiency — run-wide advisory lock:** taken for the read only, so two replicas firing at 04:20
 *    don't both pull candidates and resolve entitlements; the loser exits immediately. It is no longer a
 *    correctness guard (the per-site lock is) — just a way to avoid duplicated read work.
 *
 * Thundering-herd control: an off-peak cron (04:20, staggered past the 03:30/03:45 reapers), an oldest-first
 * [ComplyrProperties.Scan.rescanBatchSize] cap so a backlog drains over nights, and per-site jitter that
 * spreads each job's `available_at` across [ComplyrProperties.Scan.rescanJitterWindow]. The single Chromium
 * worker and [ComplyrProperties.Scan.maxJobsPerPoll] are the final backstop.
 */
@Component
class ScheduledRescanJob(
    private val siteRepository: SiteRepository,
    private val scanRepository: ScanRepository,
    private val entitlementService: EntitlementService,
    private val scanQueue: ScanQueue,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ScheduledRescanJob::class.java)

    // Drives both the leader-guarded read (selectDueSites) and each per-site enqueue as its own short
    // transaction, so one failing site is rolled back in isolation and never aborts its siblings.
    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Enqueue a re-scan for every site now due under its plan cadence. Cron-scheduled off-peak;
     * overridable via `complyr.scan.rescan-cron` (defaulted here so no yml entry is required).
     *
     * Selects the due sites in one leader-guarded read transaction, then enqueues each in its own
     * transaction (see the class KDoc). [now] is captured once so the due decision and the jittered
     * `available_at` are computed against a single instant even though the enqueues commit slightly later.
     */
    @Scheduled(cron = "\${complyr.scan.rescan-cron:$DEFAULT_RESCAN_CRON}")
    fun enqueueDueRescans() {
        val now = clock.instant()
        val dueSiteIds = transactionTemplate.execute { selectDueSites(now) } ?: emptyList()
        if (dueSiteIds.isEmpty()) return

        val jitter = SecureRandom()
        val window = properties.scan.rescanJitterWindow
        val enqueued = dueSiteIds.count { siteId -> tryEnqueue(siteId, jitteredAvailableAt(now, window, jitter)) }
        if (enqueued > 0) {
            log.info("Scheduled re-scan enqueued {} due site(s)", enqueued)
        }
    }

    /**
     * Select the sites due this run, inside the caller's read transaction. Claims the run-wide leader
     * lock (returns empty if another instance holds it), pulls the oldest-scanned candidate batch,
     * batch-resolves each owner's plan, and keeps only the sites actually due under their exact cadence.
     * No enqueues happen here — the lock releases at this transaction's commit and each enqueue then runs
     * on its own (see [tryEnqueue]).
     */
    private fun selectDueSites(now: Instant): List<UUID> {
        if (!scanRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
            log.debug("Skipping scheduled re-scan; another instance holds the lock")
            return emptyList()
        }
        // Coarse SQL pre-filter on the SHORTEST cadence, widened by DST_SAFETY_MARGIN; the exact per-plan
        // cadence is applied below, so no plan logic leaks into SQL. A guard test pins every cadence >=
        // SHORTEST_CADENCE. resolveAll and filter both no-op on an empty candidate set.
        val cutoff = now.minus(SHORTEST_CADENCE).plus(DST_SAFETY_MARGIN)
        val candidates = siteRepository.findRescanCandidates(cutoff, properties.scan.rescanBatchSize)
        val entitlements = entitlementService.resolveAll(candidates.map { it.userId })
        return candidates.filter { isDue(it, entitlements[it.userId], now) }.map { it.siteId }
    }

    /**
     * Enqueue one due site in its own transaction, returning whether a scan was actually created.
     * A single site failing — a transient DB error, a lock timeout — is caught here and dropped from
     * this run rather than allowed to abort the batch: no queued row was created for it, so the next
     * night's run simply re-considers it. Only Spring's data/transaction failures are swallowed; a
     * programming error propagates.
     */
    private fun tryEnqueue(
        siteId: UUID,
        availableAt: Instant,
    ): Boolean =
        try {
            transactionTemplate.execute { enqueueDueSite(siteId, availableAt) } == true
        } catch (ex: NestedRuntimeException) {
            log.warn("Scheduled re-scan skipped site {}; will retry next run", siteId, ex)
            false
        }

    /**
     * The check-then-enqueue for one site, inside its own transaction. Mirrors [ScanRequestService.request]:
     * take the per-site advisory lock, re-check for a live scan (so a concurrent manual re-scan or a second
     * replica can't produce a duplicate), and enqueue a `SCHEDULED` scan only if none is in flight. Returns
     * false when a live scan already exists.
     */
    private fun enqueueDueSite(
        siteId: UUID,
        availableAt: Instant,
    ): Boolean {
        scanRepository.acquireSiteScanLock(siteLockKey(siteId))
        if (scanRepository.existsBySiteIdAndStatusIn(siteId, ScanRequestService.LIVE_STATUSES)) return false
        scanQueue.enqueue(siteId, ScanTrigger.SCHEDULED, availableAt)
        return true
    }

    /**
     * Whether [candidate] should be re-scanned this run. Skipped when its owner has no live entitlement
     * (a since-deleted account) or an [AccountEntitlement.Expired] one — the plan freezes the dashboard,
     * "no new sites, no scans". Otherwise a never-scanned site is always due, and a scanned one is due
     * once its plan cadence has elapsed. The cadence is a [Period] (calendar months/weeks), which an
     * [Instant] can't add directly, so it is applied in the clock's zone.
     */
    private fun isDue(
        candidate: RescanCandidate,
        entitlement: AccountEntitlement?,
        now: Instant,
    ): Boolean {
        if (entitlement == null || entitlement is AccountEntitlement.Expired) return false
        val lastScanAt = candidate.lastScanAt ?: return true
        val interval = entitlement.entitlements.rescanFrequency.interval
        val dueAt = lastScanAt.atZone(clock.zone).plus(interval).toInstant()
        return !now.isBefore(dueAt)
    }

    /**
     * A random point in `[now, now + window)` for a job's `available_at`, so a nightly batch of due
     * sites doesn't all become claimable at once. A zero window (jitter disabled) returns [now].
     */
    private fun jitteredAvailableAt(
        now: Instant,
        window: Duration,
        random: SecureRandom,
    ): Instant {
        if (window.isZero) return now
        val offsetMillis = (random.nextDouble() * window.toMillis()).toLong()
        return now.plusMillis(offsetMillis)
    }

    // Fold the 128-bit site id into the 64-bit key pg_advisory_xact_lock takes (identical to
    // ScanRequestService.advisoryLockKey, so the manual and scheduled paths serialize on the same key);
    // a rare collision only costs harmless extra serialization.
    private fun siteLockKey(siteId: UUID): Long = siteId.mostSignificantBits xor siteId.leastSignificantBits

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes the read
         * across replicas. Kept distinct from every other `pg_advisory*` key the app takes:
         * [PublicScanReaper] 7_213_884_559, `StripeWebhookReaper` 8_431_907_662,
         * `ConsentIdempotencyReaper` 4_827_913_006, `ConsentEventPartitionReaper` 5_902_447_183.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 6_538_192_074L

        // The SHORTEST plan cadence (weekly). The SQL pre-filter uses it so it never excludes a site any
        // plan would consider due; the exact per-plan cadence is re-checked in Kotlin. A guard test asserts
        // every RescanFrequency.interval stays >= this, so the fixed cutoff can't silently go stale if a
        // shorter (e.g. daily) tier is ever added.
        private val SHORTEST_CADENCE: Duration = Duration.ofDays(7)

        // A calendar week added in-zone is 7 calendar days, which across a spring-forward DST transition is
        // only 167 real hours — just short of the 168h SHORTEST_CADENCE. Widening the coarse cutoff by a day
        // (so it selects sites scanned as recently as 6 days ago) keeps a genuinely-due weekly site from
        // slipping past the pre-filter for ~24h twice a year. The exact per-plan check in isDue still gates
        // the actual enqueue, so the wider net only costs a few extra candidates to inspect, never a wrong
        // enqueue.
        private val DST_SAFETY_MARGIN: Duration = Duration.ofDays(1)

        // 04:20 daily (server zone): past the 03:30/03:45 nightly reapers so the sweeps don't contend, and
        // off the traffic peak. Overridable via complyr.scan.rescan-cron.
        private const val DEFAULT_RESCAN_CRON = "0 20 4 * * *"
    }
}
