package eu.cookiekeeper.scan

import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Write side of the scan API: the customer-initiated "Re-scan now" action. Sits beside the read-only
 * [ScanQueryService] and shares its ownership-first posture — another user's site id is a 404, never a
 * 403, so the endpoint can't be used to enumerate site ids.
 *
 * The throttle is structural rather than a counter: a per-site advisory lock plus a live-scan check means
 * a site can never hold two queued/running scans, so a caller looping the endpoint just collects 409s.
 * That is why `POST .../scans` deliberately has no tight rate-limit tier of its own — it shares a path
 * with the scan list the dashboard polls every 3 seconds, and
 * [eu.cookiekeeper.common.AuthenticatedRateLimitFilter] matches on path only.
 */
@Service
class ScanRequestService(
    private val siteRepository: SiteRepository,
    private val scanRepository: ScanRepository,
    private val entitlementService: EntitlementService,
    private val scanQueue: ScanQueue,
    private val clock: Clock,
) {
    /**
     * Queue an immediate re-scan of a site the caller owns, returning the new scan id.
     *
     * Ordering is deliberate. Ownership is checked first (404 for anyone else's site), then the
     * **entitlement before any state**: a Starter user must get the same upgrade prompt whether or not a
     * scan happens to be running, otherwise the 409 leaks that the feature would have worked. Only then
     * do we take the per-site lock and re-check the site's status and for a live scan.
     *
     * The status re-check happens AFTER the lock, against [SiteRepository.findStatusById] rather than
     * trusting the ownership lookup above: [AccountDeletionService][eu.cookiekeeper.account.AccountDeletionService]
     * archives a site under a per-USER lock this method never takes, so a concurrent erasure can commit
     * between the ownership check and this point. The re-check MUST be a projection query, not another
     * entity find by id — this whole method runs in one transaction/persistence context, and a second
     * `EntityManager.find()` for an id already loaded above would return the SAME cached managed instance
     * instead of re-querying, silently defeating the whole re-check (see [SiteRepository.findStatusById]'s
     * doc). The projection has no such identity-map short-circuit, so it genuinely observes a concurrent
     * erasure's committed archive instead of enqueuing a scan for a site it already tombstoned.
     *
     * Transactional so the lock, the check and the enqueue are one atomic decision — the lock releases at
     * commit, and [ScanQueue.enqueue]'s own `@Transactional` joins this one rather than opening a second.
     * Every call here is local: the crawl itself happens later, in the scanner worker.
     *
     * `ThrowsCount` is suppressed for the four refusals: folding any of them into a shared guard would
     * either reorder the entitlement check or blur two 404s that mean different things to the reader.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun request(
        userId: UUID,
        siteId: UUID,
    ): UUID {
        siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
        entitlementService.requireOnDemandRescan(userId)

        scanRepository.acquireSiteScanLock(advisoryLockKey(siteId))
        // Re-read under the lock (see the method doc): an archived site has no widget and nothing to
        // keep current, and 404 — not 409/403 — keeps it indistinguishable from a site that never
        // existed, matching the rest of the site surface.
        if (siteRepository.findStatusById(siteId) != SiteStatus.ACTIVE) throw SiteNotFoundException()
        if (scanRepository.existsBySiteIdAndStatusIn(siteId, LIVE_STATUSES)) throw ScanAlreadyInProgressException()

        // Stamp the claim-ordering tier from the owner's plan. Resolved here (not inside the queue) so the
        // queue carries no billing dependency; the owner provably exists past requireOnDemandRescan, so a
        // plain resolve is safe. Business gets priority; Pro (the other on-demand tier) enqueues normal.
        val priority = ScanQueue.priorityFor(entitlementService.resolve(userId).entitlements.priorityScan)
        return scanQueue.enqueue(siteId, ScanTrigger.MANUAL, clock.instant(), priority)
    }

    // Fold the 128-bit site id into the 64-bit key pg_advisory_xact_lock takes (mirrors
    // EntitlementService.requireCanAddSite); a rare collision only costs harmless extra serialization.
    private fun advisoryLockKey(siteId: UUID): Long = siteId.mostSignificantBits xor siteId.leastSignificantBits

    companion object {
        /** A scan that has not reached a terminal state yet — see `idx_scans_site_id_live` (V16). */
        val LIVE_STATUSES = listOf(ScanStatus.QUEUED, ScanStatus.RUNNING)
    }
}
