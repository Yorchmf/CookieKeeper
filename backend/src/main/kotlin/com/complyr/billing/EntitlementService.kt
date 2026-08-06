package com.complyr.billing

import com.complyr.auth.UserRepository
import com.complyr.common.UnauthenticatedException
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The single source of truth for what an account is allowed to do, resolved from its billing state.
 * Wraps [PlanResolver] with the account's creation time and (single) subscription row so every caller
 * only needs a userId: enforcement points call the specific `require*` guards, and the dashboard reads
 * [resolve] to surface trial / active-plan / must-subscribe.
 *
 * Deliberately does NOT touch the consent-ingestion path. Recording a visitor's consent is append-only
 * audit evidence and must never be gated by billing (CLAUDE.md constraint #3): the trial
 * [Entitlements.consentEventCap] is a dashboard signal, not an ingestion block. On-demand rescan
 * ([requireOnDemandRescan]) and CSV export ([requireCsvExport]) are enforced here; retention pruning and
 * widget branding attach as they land — each is a single [resolve]`(userId).entitlements` read away.
 */
@Service
class EntitlementService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val siteRepository: SiteRepository,
    private val planResolver: PlanResolver,
) {
    /**
     * The account's effective billing state. Throws [UnauthenticatedException] when the user row is
     * gone (a valid JWT whose subject no longer exists), mirroring the rest of the authed surface.
     */
    fun resolve(userId: UUID): AccountEntitlement {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        return planResolver.resolve(user.createdAt, subscriptionRepository.findByUserId(userId))
    }

    /**
     * Batch-resolve the billing state of many accounts in two queries (one user fetch, one subscription
     * fetch), for callers that hold a set of userIds and must not N+1 — the scheduled re-scan job
     * ([com.complyr.scan.ScheduledRescanJob]) resolves a whole candidate batch this way.
     *
     * Unlike [resolve], a missing user row is silently dropped rather than throwing: a batch of
     * background candidates isn't a single authenticated request, and a since-deleted account is simply
     * not entitled to anything. Callers get no entry for such ids and skip them.
     */
    fun resolveAll(userIds: Collection<UUID>): Map<UUID, AccountEntitlement> {
        if (userIds.isEmpty()) return emptyMap()
        val distinct = userIds.toSet()
        val subscriptionsByUser = subscriptionRepository.findAllByUserIdIn(distinct).associateBy { it.userId }
        return userRepository
            .findAllById(distinct)
            .associate { it.id to planResolver.resolve(it.createdAt, subscriptionsByUser[it.id]) }
    }

    /**
     * [resolve] plus current usage counters, for the dashboard billing read. Read-only, so no lock is
     * taken (unlike [requireCanAddSite]); a slightly-stale active-site count in a display-only summary
     * is harmless.
     */
    fun summarize(userId: UUID): EntitlementSummary =
        EntitlementSummary(
            entitlement = resolve(userId),
            activeSites = siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE),
        )

    /**
     * Guard the site-create path against the plan's site cap. Throws [SiteLimitReachedException] once
     * the account already has at least [Entitlements.maxSites] ACTIVE sites — which also freezes new
     * sites for an Expired account (cap 0).
     *
     * Must run inside the create transaction: a per-user transaction-scoped advisory lock is taken
     * before the count read so concurrent creates for one account serialize, closing the check-then-act
     * race (there is no DB-level per-plan constraint to backstop the count). This gates NEW sites only —
     * a later downgrade or trial expiry never tears down existing sites (CLAUDE.md constraint #3), so
     * "≤ maxSites active sites" is a create-time predicate, not a continuously enforced invariant.
     */
    fun requireCanAddSite(userId: UUID) {
        siteRepository.acquireUserSiteLock(advisoryLockKey(userId))
        val cap = resolve(userId).entitlements.maxSites
        val activeSites = siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE)
        if (activeSites >= cap) throw SiteLimitReachedException()
    }

    /**
     * Guard the consent-log CSV export against the plan's [Entitlements.csvExport] flag (Business-only).
     * Throws [CsvExportNotEntitledException] (403) otherwise. Read-only — export is a dashboard feature, never
     * on the consent-ingestion path, so no lock is needed.
     */
    fun requireCsvExport(userId: UUID) {
        if (!resolve(userId).entitlements.csvExport) throw CsvExportNotEntitledException()
    }

    /**
     * Guard the "Re-scan now" action against the plan's [Entitlements.onDemandRescan] flag (Pro and
     * Business). Throws [OnDemandRescanNotEntitledException] (403) otherwise, which also freezes it for an
     * Expired account. Read-only — the scan queue's own advisory lock is what serializes the enqueue, so
     * no lock is taken here.
     *
     * Gates *immediacy* only: every plan keeps its [Entitlements.rescanFrequency] scheduled re-scan, so a
     * Starter site is never stuck with the single scan it got at signup.
     */
    fun requireOnDemandRescan(userId: UUID) {
        if (!resolve(userId).entitlements.onDemandRescan) throw OnDemandRescanNotEntitledException()
    }

    // Fold the 128-bit user id into the 64-bit key pg_advisory_xact_lock takes (mirrors PolicyService);
    // a rare collision with another key space only causes harmless extra serialization, never a miss.
    private fun advisoryLockKey(userId: UUID): Long = userId.mostSignificantBits xor userId.leastSignificantBits
}

/** An account's [AccountEntitlement] paired with its current usage counters (see [EntitlementService.summarize]). */
data class EntitlementSummary(
    val entitlement: AccountEntitlement,
    val activeSites: Long,
)
