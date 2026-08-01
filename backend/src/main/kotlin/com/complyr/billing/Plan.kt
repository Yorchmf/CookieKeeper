package com.complyr.billing

import java.time.Instant
import java.time.Period

/** How often a site's cookies are automatically re-scanned. */
enum class RescanFrequency { MONTHLY, WEEKLY }

/**
 * The concrete limits enforced across the app for a given billing state — site create (count cap),
 * scan enqueue (rescan cadence / on-demand / priority), consent retention, dashboard branding, CSV
 * export. Mirrors the pricing table in docs/ARCHITECTURE.md §10. [consentEventCap] bounds how many
 * consent events an account may INGEST (null = unbounded, all paid plans); it never blocks recording
 * an already-accepted event — that would drop audit evidence (CLAUDE.md constraint #3).
 */
data class Entitlements(
    val maxSites: Int,
    val rescanFrequency: RescanFrequency,
    val onDemandRescan: Boolean,
    val priorityScan: Boolean,
    val consentRetention: Period,
    val removeBranding: Boolean,
    val csvExport: Boolean,
    val consentEventCap: Long?,
)

/**
 * Post-trial, unsubscribed accounts: the dashboard is frozen (no new sites, no scans) but the widget
 * keeps recording consent — we never drop audit evidence to apply billing pressure. Retention holds
 * at the entry (12-month) tier so existing evidence ages out normally.
 */
val EXPIRED_ENTITLEMENTS =
    Entitlements(
        maxSites = 0,
        rescanFrequency = RescanFrequency.MONTHLY,
        onDemandRescan = false,
        priorityScan = false,
        consentRetention = Period.ofMonths(RETENTION_MONTHS_ENTRY),
        removeBranding = false,
        csvExport = false,
        consentEventCap = null,
    )

private const val RETENTION_MONTHS_ENTRY = 12
private const val RETENTION_YEARS_PAID = 3
private const val MAX_SITES_STARTER = 1
private const val MAX_SITES_PRO = 3
private const val MAX_SITES_BUSINESS = 10

/**
 * The purchasable subscription plans (docs/ARCHITECTURE.md §10). Each carries its entitlement limits;
 * the enum name is what persists in `subscriptions.plan` (stored via `@Enumerated(STRING)`).
 */
enum class Plan(
    val entitlements: Entitlements,
) {
    STARTER(
        Entitlements(
            maxSites = MAX_SITES_STARTER,
            rescanFrequency = RescanFrequency.MONTHLY,
            onDemandRescan = false,
            priorityScan = false,
            consentRetention = Period.ofMonths(RETENTION_MONTHS_ENTRY),
            removeBranding = false,
            csvExport = false,
            consentEventCap = null,
        ),
    ),
    PRO(
        Entitlements(
            maxSites = MAX_SITES_PRO,
            rescanFrequency = RescanFrequency.WEEKLY,
            onDemandRescan = true,
            priorityScan = false,
            consentRetention = Period.ofYears(RETENTION_YEARS_PAID),
            removeBranding = true,
            csvExport = false,
            consentEventCap = null,
        ),
    ),
    BUSINESS(
        Entitlements(
            maxSites = MAX_SITES_BUSINESS,
            rescanFrequency = RescanFrequency.WEEKLY,
            onDemandRescan = true,
            priorityScan = true,
            consentRetention = Period.ofYears(RETENTION_YEARS_PAID),
            removeBranding = true,
            csvExport = true,
            consentEventCap = null,
        ),
    ),
}

/**
 * The effective billing state of an account, resolved by [PlanResolver] from its subscription row and
 * trial window. [entitlements] is what the rest of the app enforces against; the variant additionally
 * tells the dashboard which state to surface (trialing / active plan / must-subscribe).
 */
sealed interface AccountEntitlement {
    val entitlements: Entitlements

    /**
     * Inside the 14-day, no-card trial: Starter-shaped limits, but consent ingestion is capped
     * ([Entitlements.consentEventCap] set from config) so the free trial can't be used as unbounded
     * production capacity.
     */
    data class Trial(
        val endsAt: Instant,
        override val entitlements: Entitlements,
    ) : AccountEntitlement

    /** An active (or Stripe-`trialing`) paid subscription. */
    data class Subscribed(
        val plan: Plan,
    ) : AccountEntitlement {
        override val entitlements: Entitlements get() = plan.entitlements
    }

    /** Trial elapsed with no active subscription — dashboard frozen, consent still recorded. */
    data object Expired : AccountEntitlement {
        override val entitlements: Entitlements get() = EXPIRED_ENTITLEMENTS
    }
}
