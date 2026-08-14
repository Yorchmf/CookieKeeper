package com.complyr.analytics.dto

import java.time.Instant
import java.util.UUID

/**
 * The account-level dashboard home (`GET /api/v1/overview`): headline figures summed across every ACTIVE
 * site the caller owns, plus the concrete things needing their attention.
 *
 * Scoped to ACTIVE sites only — an archived site is one the customer has retired, so counting it would
 * inflate the figures and nagging about it in [actions] would be noise. (Note this differs from the trial
 * usage meter, which deliberately includes archived sites because the events they ingested still spent the
 * allowance.)
 *
 * Deliberately does NOT carry billing state: the dashboard already reads that from
 * `GET /api/v1/billing/entitlement`, and the trial strip renders from the same cache. Duplicating it here
 * would give the page two sources of truth for whether the account is trialing.
 */
data class AccountOverviewResponse(
    val range: AnalyticsRange,
    val headline: OverviewHeadline,
    val actions: List<OverviewAction>,
    val onboarding: OnboardingProgress,
)

/**
 * First-run progress for the dashboard's getting-started checklist: has the account walked a site through
 * add → scan → customise banner → verify. Each flag is account-wide ("has ANY active site reached this
 * step"), which is exact for the single-site accounts onboarding targets and lenient — never blocking — for
 * the rare multi-site case, where a specific site's full state lives on its own page.
 *
 * Every flag is false for an account with no sites, so the checklist is the natural first-run surface. The
 * client hides it once all four are true; the server does not carry a `complete` flag because that is a
 * trivial `&&` the client can derive, and one fewer field is one fewer thing to keep in sync.
 *
 * Deliberately account-wide and unwindowed: "have you done this yet" is a lifetime question, unlike the
 * consent figures in [OverviewHeadline].
 */
data class OnboardingProgress(
    val addedSite: Boolean,
    val scanned: Boolean,
    val customisedBanner: Boolean,
    val verified: Boolean,
)

/**
 * Cross-site headline figures. Consent figures cover [AccountOverviewResponse.range]; the cookie and scan
 * figures are point-in-time (each site's most recent completed scan), not windowed — a scan from before the
 * window still describes what is on the site today.
 *
 * [acceptAllRate] is the share of decisions in the window that were a full accept-all, and is null (rather
 * than 0.0) when no decisions were recorded — "no data" and "nobody accepted" are different facts and the
 * UI renders them differently. It deliberately excludes partial/custom consents: calling those acceptances
 * would overstate opt-in on a product that sells honest consent reporting.
 */
data class OverviewHeadline(
    val activeSites: Int,
    val consentEvents: Long,
    val acceptAllRate: Double?,
    val cookiesFound: Int,
    val lastScanAt: Instant?,
)

/**
 * What a site needs from the customer. DECLARATION ORDER IS SEVERITY ORDER — the service sorts by ordinal
 * and emits at most one action per site, so adding a value in the wrong place silently re-prioritises the
 * list. Rationale for this order: an unverified site means we cannot confirm the widget is even live, so
 * nothing downstream of it can be trusted; a never-scanned site means we do not know what it sets; a
 * missing policy is a document the law requires; a stale policy is a document that exists but predates what
 * we last found; insecure cookies are a real finding but the least likely to be actionable today.
 */
enum class OverviewActionKind {
    UNVERIFIED,
    NEVER_SCANNED,
    POLICY_MISSING,
    POLICY_STALE,
    INSECURE_COOKIES,
}

/**
 * One site that needs attention. [kind] is serialized lowercase (matching `EntitlementResponse`'s enum
 * convention) and is used by the dashboard as an i18n key suffix, so it is a closed set, not free text.
 * [count] carries the magnitude where one exists (the insecure-cookie count) and is null otherwise.
 *
 * At most one action is reported per site — the most severe. The site's own detail page shows the full
 * picture; the home page answers "which sites need me, and why first".
 */
data class OverviewAction(
    val kind: String,
    val siteId: UUID,
    val domain: String,
    val count: Int? = null,
) {
    companion object {
        fun of(
            kind: OverviewActionKind,
            siteId: UUID,
            domain: String,
            count: Int? = null,
        ): OverviewAction = OverviewAction(kind.name.lowercase(), siteId, domain, count)
    }
}
