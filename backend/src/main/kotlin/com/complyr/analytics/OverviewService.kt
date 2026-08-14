package com.complyr.analytics

import com.complyr.analytics.dto.AccountOverviewResponse
import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.OnboardingProgress
import com.complyr.analytics.dto.OverviewAction
import com.complyr.analytics.dto.OverviewActionKind
import com.complyr.analytics.dto.OverviewHeadline
import com.complyr.banner.ConsentCategory
import com.complyr.banner.DefaultBannerConfig
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read side of the dashboard home: everything the account owns, rolled up into a few headline figures plus
 * the concrete things needing attention.
 *
 * Ownership is scoped by construction — the site set is fetched by `userId` and every subsequent query is
 * keyed on those ids — so unlike [AnalyticsService] there is no id to smuggle in and nothing to gate.
 *
 * The per-site data is read in three BATCH queries ([OverviewRepository]) rather than by looping the
 * per-site finders: this is the page every customer lands on, and an account with ten sites would otherwise
 * cost thirty round-trips. Read-only throughout; nothing here touches the consent-ingestion path (CLAUDE.md #3).
 */
@Service
class OverviewService(
    private val siteRepository: SiteRepository,
    private val overviewRepository: OverviewRepository,
    private val consentAnalyticsRepository: ConsentAnalyticsRepository,
    private val rangeResolver: AnalyticsRangeResolver,
) {
    @Transactional(readOnly = true)
    fun overview(
        userId: UUID,
        filter: AnalyticsFilter,
    ): AccountOverviewResponse {
        val range = rangeResolver.resolve(userId, filter)
        val sites = siteRepository.findAllByUserIdAndStatus(userId, SiteStatus.ACTIVE)

        // Early return before any batch query: `IN ()` is not valid SQL, and a brand-new account with no
        // sites is the single most common state on this page. An empty account has completed no step, so
        // the checklist shows every one still to do.
        if (sites.isEmpty()) return AccountOverviewResponse(range, EMPTY_HEADLINE, emptyList(), EMPTY_ONBOARDING)

        val siteIds = sites.map { it.id }
        val scans = overviewRepository.latestCompletedScans(siteIds).associateBy { it.siteId }
        val cookies =
            if (scans.isEmpty()) {
                emptyMap()
            } else {
                overviewRepository
                    .cookieTotals(scans.values.map { it.scanId }, ConsentCategory.NECESSARY.key)
                    .associateBy { it.scanId }
            }
        val policies = overviewRepository.latestPublishedPolicies(siteIds).associateBy { it.siteId }
        val bannerVersions = overviewRepository.maxBannerVersions(siteIds).associateBy { it.siteId }
        val consent = consentAnalyticsRepository.accountActionCounts(siteIds, range.from, range.to)

        return AccountOverviewResponse(
            range = range,
            headline = headline(sites, scans, cookies, consent),
            actions = sites.mapNotNull { action(it, scans, cookies, policies) }.sortedBy { severity(it) },
            onboarding = onboarding(sites, scans, bannerVersions),
        )
    }

    /**
     * Account-wide first-run progress: for each step, has ANY active site reached it. Reuses the same batch
     * maps as the headline and action list, plus one banner-version read — no per-site round-trips.
     *
     * "Verified" doubles as "widget embedded": snippet-on-homepage is itself a verification method, and we
     * have no independent live-widget heartbeat, so verification is the honest proxy for the embed step.
     */
    private fun onboarding(
        sites: List<SiteEntity>,
        scans: Map<UUID, LatestScanRow>,
        bannerVersions: Map<UUID, BannerVersionRow>,
    ): OnboardingProgress =
        OnboardingProgress(
            addedSite = sites.isNotEmpty(),
            scanned = sites.any { scans.containsKey(it.id) },
            // A version past the seeded v1 is an edit the customer made; the seed alone is not "customised".
            // (Relies on the invariant that only customer edits bump banner version — no background job does.)
            customisedBanner =
                sites.any { site ->
                    val seedVersion = DefaultBannerConfig.FIRST_VERSION
                    (bannerVersions[site.id]?.version ?: seedVersion) > seedVersion
                },
            verified = sites.any { it.verifiedAt != null },
        )

    private fun headline(
        sites: List<SiteEntity>,
        scans: Map<UUID, LatestScanRow>,
        cookies: Map<UUID, ScanCookieTotals>,
        consent: List<ActionCountRow>,
    ): OverviewHeadline {
        val decisions = consent.sumOf { it.count }
        val acceptAll = consent.firstOrNull { it.action == AnalyticsService.ACTION_ACCEPT_ALL }?.count ?: 0L
        return OverviewHeadline(
            activeSites = sites.size,
            consentEvents = decisions,
            // Null, not 0.0, when nothing was recorded: "no traffic yet" must not render as "0% accepted".
            acceptAllRate = if (decisions == 0L) null else acceptAll.toDouble() / decisions,
            cookiesFound = scans.values.sumOf { cookies[it.scanId]?.total ?: 0 },
            lastScanAt = scans.values.maxOfOrNull { it.scannedAt },
        )
    }

    /**
     * The single most severe thing wrong with one site, or null if it is in good shape. At most one action
     * per site keeps the home page a to-do list rather than a wall — the site's own page has the full picture.
     *
     * Order matches [OverviewActionKind]'s declaration and the checks below must stay in that order.
     */
    private fun action(
        site: SiteEntity,
        scans: Map<UUID, LatestScanRow>,
        cookies: Map<UUID, ScanCookieTotals>,
        policies: Map<UUID, LatestPolicyRow>,
    ): OverviewAction? {
        if (site.verifiedAt == null) return of(OverviewActionKind.UNVERIFIED, site)
        val scan = scans[site.id] ?: return of(OverviewActionKind.NEVER_SCANNED, site)
        val policy = policies[site.id] ?: return of(OverviewActionKind.POLICY_MISSING, site)

        // Stale means the published policy predates what the scan found — it cannot describe cookies
        // discovered after it was written, which is exactly the disclosure gap a regulator would look for.
        if (policy.publishedAt.isBefore(scan.scannedAt)) return of(OverviewActionKind.POLICY_STALE, site)

        val insecure = cookies[scan.scanId]?.insecure ?: 0
        if (insecure > 0) return of(OverviewActionKind.INSECURE_COOKIES, site, insecure)
        return null
    }

    private fun of(
        kind: OverviewActionKind,
        site: SiteEntity,
        count: Int? = null,
    ): OverviewAction = OverviewAction.of(kind, site.id, site.domain, count)

    // The wire form is a lowercase string, so severity is recovered from the enum rather than re-listed here.
    private fun severity(action: OverviewAction): Int = OverviewActionKind.valueOf(action.kind.uppercase()).ordinal

    companion object {
        private val EMPTY_HEADLINE =
            OverviewHeadline(
                activeSites = 0,
                consentEvents = 0,
                acceptAllRate = null,
                cookiesFound = 0,
                lastScanAt = null,
            )

        private val EMPTY_ONBOARDING =
            OnboardingProgress(
                addedSite = false,
                scanned = false,
                customisedBanner = false,
                verified = false,
            )
    }
}
