package eu.cookiekeeper.analytics.dto

import eu.cookiekeeper.scan.dto.ComplianceIssue
import java.time.Instant
import java.util.UUID

/**
 * The self-describing manifest at the root of a compliance evidence pack (Track 4). It is an English,
 * structured document — deliberately mirroring the Art. 20 [eu.cookiekeeper.account.dto.AccountExport]
 * convention where field *names* carry the meaning. This file is a downloadable artifact, not dashboard
 * chrome, so it is not routed through i18n (the dashboard's download button and confirmation modal are).
 */
data class EvidenceManifest(
    /** Format marker so a future v2 of the pack can be told apart by anything that consumes it. */
    val format: String = FORMAT,
    val bundledAt: Instant,
    val account: EvidenceAccount,
    val site: EvidenceSite,
    /** The entry paths actually written into this pack; a site with nothing published omits its policy files. */
    val contents: List<String>,
    /** Trailing window, in days, of the bundled `consent-events.csv`. */
    val consentEventsWindowDays: Int,
    /**
     * Why the consent evidence is scoped the way it is: append-only audit records retained per ADR-16.
     * Plain prose because the pack is a formal document artifact — see the class note on why it is not i18n.
     */
    val retentionNotice: String,
) {
    companion object {
        const val FORMAT = "complyr-evidence-pack/v1"
    }
}

/** The account the pack belongs to — whose evidence this is (their own personal data, as in Art. 20 export). */
data class EvidenceAccount(
    val id: UUID,
    val email: String,
    val name: String?,
)

data class EvidenceSite(
    val id: UUID,
    val domain: String,
)

/**
 * The latest completed scan distilled to the compliance evidence the pack carries: the raw cookie counts
 * plus [eu.cookiekeeper.scan.ComplianceAnalyzer]'s indicative score and issues. Every field is an
 * empty-state zero/null when the site has never completed a scan, so the file is always present and states
 * "nothing scanned yet" rather than being silently absent.
 */
data class EvidenceScanReport(
    val scanId: UUID?,
    val scannedAt: Instant?,
    val totalCookies: Int,
    val knownCookies: Int,
    val unknownCookies: Int,
    val insecureCookies: Int,
    val marketingTrackerCount: Int,
    /** Indicative 0–100 (higher is better); null when there is no completed scan to score. */
    val complianceScore: Int?,
    val issues: List<ComplianceIssue>,
) {
    companion object {
        /** What a site that has never completed a scan carries — present, but explicitly empty. */
        val EMPTY =
            EvidenceScanReport(
                scanId = null,
                scannedAt = null,
                totalCookies = 0,
                knownCookies = 0,
                unknownCookies = 0,
                insecureCookies = 0,
                marketingTrackerCount = 0,
                complianceScore = null,
                issues = emptyList(),
            )
    }
}
