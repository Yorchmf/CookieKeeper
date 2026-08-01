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
 * [Entitlements.consentEventCap] is a dashboard signal, not an ingestion block. Enforcement for
 * on-demand rescan, CSV export, retention pruning, and widget branding attaches to those features as
 * they land — each is a single [resolve]`(userId).entitlements` read away.
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

    // Fold the 128-bit user id into the 64-bit key pg_advisory_xact_lock takes (mirrors PolicyService);
    // a rare collision with another key space only causes harmless extra serialization, never a miss.
    private fun advisoryLockKey(userId: UUID): Long = userId.mostSignificantBits xor userId.leastSignificantBits
}
