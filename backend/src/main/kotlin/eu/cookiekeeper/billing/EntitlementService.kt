package eu.cookiekeeper.billing

import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.consent.ConsentEventRepository
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 *
 * On the `billing` → `consent` package dependency this class carries ([ConsentEventRepository], for the
 * trial usage count): it is deliberately ONE-WAY and READ-ONLY. `billing` may count consent rows;
 * `consent` must never call into `billing`, or recording a visitor's choice would start depending on the
 * customer's payment state — exactly what constraint #3 forbids. Keeping the arrow pointing this way is
 * also what stops a Spring bean cycle from forming.
 */
@Service
class EntitlementService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val siteRepository: SiteRepository,
    private val consentEventRepository: ConsentEventRepository,
    private val planResolver: PlanResolver,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(EntitlementService::class.java)

    /**
     * The account's effective billing state. Throws [UnauthenticatedException] when the user row is
     * gone (a valid JWT whose subject no longer exists), mirroring the rest of the authed surface.
     */
    fun resolve(userId: UUID): AccountEntitlement = loadAccount(userId).entitlement

    /**
     * The account row's billing inputs, loaded once: its creation instant and the state [PlanResolver]
     * derives from that plus the subscription row. Shared by [resolve] (which needs only the state) and
     * [summarize] (which also needs `createdAt` to bound the trial usage count), so neither pays a second
     * user fetch and there is one definition of "resolve this account".
     */
    private data class ResolvedAccount(
        val createdAt: Instant,
        val entitlement: AccountEntitlement,
    )

    private fun loadAccount(userId: UUID): ResolvedAccount {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        return ResolvedAccount(
            createdAt = user.createdAt,
            entitlement = planResolver.resolve(user.createdAt, subscriptionRepository.findByUserId(userId)),
        )
    }

    /**
     * Batch-resolve the billing state of many accounts in two queries (one user fetch, one subscription
     * fetch), for callers that hold a set of userIds and must not N+1 — the scheduled re-scan job
     * ([eu.cookiekeeper.scan.ScheduledRescanJob]) resolves a whole candidate batch this way.
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
     * taken (unlike [requireCanAddSite]); a slightly-stale count in a display-only summary is harmless.
     *
     * Goes through [loadAccount] rather than [resolve] so the account's `createdAt` is in hand for the
     * trial usage bound without a second fetch. [EntitlementSummary.consentEventsUsed] is populated
     * only while trialing — the meter it feeds is meaningful against the trial cap alone; it is left
     * null (and the dashboard hides the meter) for subscribed / expired accounts.
     */
    fun summarize(userId: UUID): EntitlementSummary {
        val (createdAt, entitlement) = loadAccount(userId)
        return EntitlementSummary(
            entitlement = entitlement,
            activeSites = siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE),
            consentEventsUsed =
                if (entitlement is AccountEntitlement.Trial) countTrialConsentEvents(userId, createdAt) else null,
        )
    }

    /**
     * Consent events this account has recorded since [since] (its creation instant while trialing),
     * summed across all its sites. A DISPLAY-ONLY signal for the trial usage meter — never a gate on the
     * append-only consent path (CLAUDE.md #3). Guards the empty-`IN` case: a brand-new trial with no
     * sites yet has used none, and `site_id IN ()` is not valid SQL to hand the driver.
     */
    private fun countTrialConsentEvents(
        userId: UUID,
        since: Instant,
    ): Long {
        val siteIds = siteRepository.findIdsByUserId(userId)
        return if (siteIds.isEmpty()) {
            0L
        } else {
            consentEventRepository.countBySiteIdInAndCreatedAtGreaterThanEqual(siteIds, since)
        }
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
        // Re-read under the lock, because the Art. 17 erasure takes this same lock (ADR-20,
        // [eu.cookiekeeper.account.AccountDeletionService]). A create that passed the JWT filter's tombstone
        // check microseconds before the erasure committed would otherwise leave a live ACTIVE site owned
        // by a tombstone — unreachable from any dashboard and outside every future erasure.
        if (userRepository.findById(userId).orElseThrow { UnauthenticatedException() }.isErased) {
            throw UnauthenticatedException()
        }
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
     * Guard the cross-site ("All Sites") analytics roll-up against the plan's
     * [Entitlements.crossSiteAnalytics] flag (Pro and Business). Throws
     * [CrossSiteAnalyticsNotEntitledException] (403) otherwise, which also freezes it for a Trial or
     * Starter account (both single-site — an aggregate over one site is just the site view). Read-only —
     * the roll-up never touches the consent-ingestion path, so no lock is needed.
     */
    fun requireCrossSiteAnalytics(userId: UUID) {
        if (!resolve(userId).entitlements.crossSiteAnalytics) throw CrossSiteAnalyticsNotEntitledException()
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

    /**
     * The oldest `created_at` this account may read consent evidence from: now − [Entitlements.consentRetention].
     *
     * This is the *read-layer* half of retention that ADR-16 assumes exists. Physical deletion
     * ([eu.cookiekeeper.consent.ConsentEventPartitionReaper]) is deliberately tenant-blind and set to the LONGEST
     * plan window (3 yr), because a monthly partition mixes every tenant's rows and `DROP PARTITION` is the only
     * sanctioned removal path (the V4 trigger makes rows un-`DELETE`-able). ADR-16 therefore defines shorter
     * per-plan windows as a read-layer product limit, and THIS is that limit: without it a Starter account sold
     * 12-month retention could page back through the full 3 years. Every consent-evidence read clamps here.
     *
     * Applied as a floor on the query window, never as an error: asking for older data yields fewer rows, not a
     * rejection. Callers pass it through the same filter the caller supplied, so the JSON log, the CSV export
     * (which reuses the log query) and the analytics aggregates all inherit one definition of "visible history".
     *
     * Resolved at UTC because [Entitlements.consentRetention] is a [java.time.Period] (date-based months/years),
     * which `Instant` cannot subtract directly — calendar arithmetic needs a date context.
     */
    fun consentRetentionFloor(userId: UUID): Instant =
        clock
            .instant()
            .atZone(ZoneOffset.UTC)
            .minus(resolve(userId).entitlements.consentRetention)
            .toInstant()

    /**
     * Whether [ownerId]'s plan suppresses the "Powered by CookieKeeper" attribution (widget banner and
     * hosted policy page). Best-effort: defaults to `false` (show the attribution — the honest
     * free-tier default) on any failure to read billing state, so the public widget-config and
     * hosted-policy reads never 500 over a branding nicety. Fails CLOSED — a resolution error never
     * hands the paid feature out for free.
     */
    fun removeBrandingOrDefault(ownerId: UUID): Boolean =
        bestEffortFlag("branding entitlement for owner", ownerId) {
            resolve(ownerId).entitlements.removeBranding
        }

    /**
     * The EFFECTIVE branding suppression for a site: the customer's per-site preference [hideBranding]
     * AND their plan's [Entitlements.removeBranding]. The preference is short-circuited first, so a
     * customer who chose to keep the attribution never triggers a billing read, and a free-tier site
     * can prefer removal all it likes yet still shows the credit. Delegates the entitlement half to
     * [removeBrandingOrDefault], inheriting its fail-CLOSED behaviour (a billing-read blip yields
     * "show the attribution", never the paid feature for free). This is the only correct way to
     * resolve branding for a site — callers must never read [hideBranding] or the raw entitlement
     * alone, or they'd either leak the paid feature or ignore the customer's choice.
     */
    fun effectiveRemoveBranding(
        ownerId: UUID,
        hideBranding: Boolean,
    ): Boolean = hideBranding && removeBrandingOrDefault(ownerId)

    /**
     * Whether the owner of [siteId] is on a plan that grants priority scanning (Business). Best-effort:
     * defaults to `false` (the normal claim tier) if the site or its billing state can't be resolved,
     * so a scan is always enqueued rather than dropped over an SLA nicety. Fails SAFE to normal.
     */
    fun priorityScanForSite(siteId: UUID): Boolean =
        bestEffortFlag("scan priority for site", siteId) {
            val site = siteRepository.findById(siteId).orElse(null)
            site != null && resolve(site.userId).entitlements.priorityScan
        }

    /**
     * Run [block] and return its flag, falling back to `false` on any non-fatal failure (missing/
     * deleted owner via [UnauthenticatedException], transient repo error). Fatal [Error]s (OOM etc.)
     * are rethrown rather than masked. Logs the failure without a stack trace — the routine
     * deleted-owner case would otherwise spam these public, high-traffic paths.
     */
    private inline fun bestEffortFlag(
        what: String,
        id: UUID,
        block: () -> Boolean,
    ): Boolean =
        runCatching(block).getOrElse { ex ->
            if (ex is Error) throw ex
            log.warn("Could not resolve {} {}, defaulting to false: {}", what, id, ex.toString())
            false
        }

    // Fold the 128-bit user id into the 64-bit key pg_advisory_xact_lock takes (mirrors PolicyService);
    // a rare collision with another key space only causes harmless extra serialization, never a miss.
    private fun advisoryLockKey(userId: UUID): Long = userId.mostSignificantBits xor userId.leastSignificantBits
}

/**
 * An account's [AccountEntitlement] paired with its current usage counters (see
 * [EntitlementService.summarize]). [consentEventsUsed] is the trial ingestion count against
 * [Entitlements.consentEventCap] and is non-null only while trialing (null on subscribed / expired
 * accounts, where the cap does not apply).
 */
data class EntitlementSummary(
    val entitlement: AccountEntitlement,
    val activeSites: Long,
    val consentEventsUsed: Long? = null,
)
