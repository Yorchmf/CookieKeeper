package eu.cookiekeeper.account.dto

import eu.cookiekeeper.banner.BannerConfigDocument
import eu.cookiekeeper.policy.PolicyDetails
import java.time.Instant
import java.util.UUID

/**
 * The GDPR Art. 20 data-portability export: everything the account gave us or that we derived for it, as
 * one self-describing JSON document. Field names mirror the API's own vocabulary so the file reads the
 * same way the dashboard does.
 *
 * What is deliberately NOT in here:
 *  * **Stripe customer/subscription ids** — internal references belonging to our payment processor, not
 *    to the customer. Their own invoices live in the Stripe portal.
 *  * **Consent events** — those are the *visitors'* personal data, where our customer is the controller
 *    and we are the processor. They are not the customer's Art. 20 data, and the existing per-site CSV
 *    export is the right tool for them. Each site carries [SiteExport.consentEventsCsvPath] as a pointer.
 *  * **Password hashes and session tokens** — credentials, not portable data.
 */
data class AccountExport(
    /** Format marker so a future v2 can be told apart by anything that consumes this file. */
    val format: String = FORMAT,
    val exportedAt: Instant,
    val account: AccountSection,
    val subscription: SubscriptionSection?,
    val sites: List<SiteExport>,
    /**
     * Every site the account owns, including any beyond the cap [sites] is truncated to
     * ([eu.cookiekeeper.account.AccountExportService]). Equal to `sites.size` for every normal account; when
     * it is larger the document says so rather than presenting a partial export as complete.
     */
    val siteCount: Int,
) {
    companion object {
        const val FORMAT = "complyr-account-export/v1"
    }
}

data class AccountSection(
    val id: UUID,
    val email: String,
    /** Optional display name; null when the account never set one (Art. 20 portability of all personal data). */
    val name: String?,
    val locale: String,
    val createdAt: Instant,
    val verifiedAt: Instant?,
)

/** Plan state only — see [AccountExport] on why the Stripe ids are absent. */
data class SubscriptionSection(
    val plan: String,
    val status: String,
    val periodEnd: Instant?,
    val createdAt: Instant,
)

data class SiteExport(
    val id: UUID,
    val domain: String,
    val siteKey: String,
    val status: String,
    val verifiedAt: Instant?,
    val verificationMethod: String?,
    val hideBranding: Boolean,
    val createdAt: Instant,
    val bannerConfig: BannerConfigExport?,
    val policy: PolicyExport?,
    /** Where the site's consent evidence is exported from; not inlined (see [AccountExport]). */
    val consentEventsCsvPath: String,
    /** Total scans ever run for this site — compare with `scans.size` to see whether the list was capped. */
    val scanCount: Int,
    val scans: List<ScanExport>,
    /** Cookies observed by the most recent completed scan — the findings the dashboard shows today. */
    val latestScanCookies: List<ScanCookieExport>,
)

/** The published banner configuration, verbatim: the customer's own texts, colours and categories. */
data class BannerConfigExport(
    val version: Int,
    val publishedAt: Instant?,
    val config: BannerConfigDocument,
)

/**
 * The published cookie policy as metadata plus its public URL. The rendered HTML is not inlined — it is
 * a generated artifact of several hundred KB per language that is already served, unauthenticated, at
 * [hostedUrl]. [details] is the business information the customer typed, which is genuinely theirs.
 *
 * [hostedUrl] and [details] both come from the site's policy settings row and are null together if that
 * row is somehow absent — the export must not fail just because one site is in an odd state.
 */
data class PolicyExport(
    val version: Int,
    val publishedAt: Instant?,
    val languages: List<String>,
    val hostedUrl: String?,
    val details: PolicyDetails?,
)

data class ScanExport(
    val id: UUID,
    val status: String,
    val trigger: String,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val pagesCrawled: Int?,
    val marketingTrackerCount: Int?,
    val createdAt: Instant,
)

data class ScanCookieExport(
    val name: String,
    val domain: String?,
    val expiry: String?,
    val category: String?,
    val provider: String?,
    val isKnown: Boolean,
    val secure: Boolean,
    val httpOnly: Boolean,
)

/** What an Art. 17 erasure actually did, so the farewell screen can say it rather than imply it. */
data class AccountDeletionResponse(
    /** Sites removed completely — they had never recorded a consent event. */
    val sitesDeleted: Int,
    /**
     * Sites reduced to an anonymous tombstone because they hold consent evidence that must stay
     * referentially valid until it ages out (ADR-16/ADR-20). Domain, key and settings are destroyed.
     */
    val sitesAnonymized: Int,
)
