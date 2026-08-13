package com.complyr.account

import com.complyr.account.dto.BannerConfigExport
import com.complyr.account.dto.PolicyExport
import com.complyr.account.dto.ScanCookieExport
import com.complyr.account.dto.ScanExport
import com.complyr.account.dto.SiteExport
import com.complyr.banner.BannerConfigRepository
import com.complyr.common.ComplyrProperties
import com.complyr.policy.PolicyRepository
import com.complyr.policy.PolicySettingsRepository
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanEntity
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.site.SiteEntity
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Builds the per-site half of the Art. 20 export ([AccountExportService]).
 *
 * Split out of the service purely so neither class carries a ten-collaborator constructor: the account
 * level needs users/subscriptions/sites, the site level needs banner/policy/scan storage. It reads only —
 * nothing here writes, and every lookup is already scoped to a site the caller proved ownership of.
 */
@Component
class SiteExportAssembler(
    private val bannerConfigRepository: BannerConfigRepository,
    private val policyRepository: PolicyRepository,
    private val policySettingsRepository: PolicySettingsRepository,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val properties: ComplyrProperties,
) {
    fun assemble(site: SiteEntity): SiteExport {
        val scans = scanRepository.findBySiteIdOrderByCreatedAtDesc(site.id, PageRequest.of(0, MAX_SCANS_PER_SITE))
        return SiteExport(
            id = site.id,
            domain = site.domain,
            siteKey = site.siteKey,
            status = site.status.dbValue,
            verifiedAt = site.verifiedAt,
            verificationMethod = site.verificationMethod?.dbValue,
            hideBranding = site.hideBranding,
            createdAt = site.createdAt,
            bannerConfig = bannerConfig(site.id),
            policy = policy(site.id),
            consentEventsCsvPath = "/api/v1/sites/${site.id}/consent-events/export.csv",
            scanCount = scanRepository.countBySiteId(site.id).toInt(),
            scans = scans.map(::scanExport),
            latestScanCookies = latestScanCookies(site.id),
        )
    }

    private fun bannerConfig(siteId: UUID): BannerConfigExport? =
        bannerConfigRepository
            .findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId)
            ?.let { BannerConfigExport(version = it.version, publishedAt = it.publishedAt, config = it.config) }

    /**
     * The current published policy version, as metadata. All language rows of that version share a
     * version number, so the languages are read from the sibling rows rather than the head row alone.
     */
    private fun policy(siteId: UUID): PolicyExport? {
        val head = policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) ?: return null
        val settings = policySettingsRepository.findById(siteId).orElse(null)
        return PolicyExport(
            version = head.version,
            publishedAt = head.publishedAt,
            languages =
                policyRepository
                    .findBySiteIdAndVersion(siteId, head.version)
                    .map { it.language }
                    .sorted(),
            hostedUrl = settings?.let { "${properties.appBaseUrl}/p/${it.publicId}" },
            details = settings?.details,
        )
    }

    /**
     * Cookies from the site's most recent COMPLETED scan only. Every scan keeps its own findings, but
     * exporting all of them would multiply an unbounded history by dozens of rows each for no portability
     * gain — the latest crawl is what the dashboard and the generated policy actually reflect.
     */
    private fun latestScanCookies(siteId: UUID): List<ScanCookieExport> {
        val latestDone =
            scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE)
                ?: return emptyList()
        return scanCookieRepository.findByScanId(latestDone.id).map {
            ScanCookieExport(
                name = it.name,
                domain = it.domain,
                expiry = it.expiry,
                category = it.category,
                provider = it.provider,
                isKnown = it.isKnown,
                secure = it.secure,
                httpOnly = it.httpOnly,
            )
        }
    }

    private fun scanExport(scan: ScanEntity): ScanExport =
        ScanExport(
            id = scan.id,
            status = scan.status.dbValue,
            trigger = scan.trigger.dbValue,
            startedAt = scan.startedAt,
            finishedAt = scan.finishedAt,
            pagesCrawled = scan.pagesCrawled,
            marketingTrackerCount = scan.marketingTrackerCount,
            createdAt = scan.createdAt,
        )

    private companion object {
        /**
         * Newest-first cap on the exported scan history. A long-lived site on the weekly cadence accrues
         * ~52 scans a year, and the export is assembled fully in memory before it is written; the cap keeps
         * a heavy account's document bounded. `scanCount` reports the real total so the truncation is
         * never silent.
         */
        const val MAX_SCANS_PER_SITE = 100
    }
}
