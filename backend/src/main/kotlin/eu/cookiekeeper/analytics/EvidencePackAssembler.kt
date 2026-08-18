package eu.cookiekeeper.analytics

import eu.cookiekeeper.analytics.dto.EvidenceAccount
import eu.cookiekeeper.analytics.dto.EvidenceManifest
import eu.cookiekeeper.analytics.dto.EvidenceScanReport
import eu.cookiekeeper.analytics.dto.EvidenceSite
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.banner.ConsentCategory
import eu.cookiekeeper.consent.ConsentCsvExportService
import eu.cookiekeeper.consent.dto.ConsentLogFilter
import eu.cookiekeeper.policy.PolicyEntity
import eu.cookiekeeper.policy.PolicyRepository
import eu.cookiekeeper.scan.ComplianceAnalyzer
import eu.cookiekeeper.scan.ScanCookieRepository
import eu.cookiekeeper.scan.ScanRepository
import eu.cookiekeeper.scan.ScanStatus
import eu.cookiekeeper.site.SiteEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams the contents of a compliance evidence pack into a [ZipOutputStream]. Assumes its caller
 * ([ComplianceEvidenceService]) has already resolved the entitlement + ownership gates and the (non-erased)
 * account — this type does no authorization, only assembly.
 *
 * The consent CSV is streamed in keyset-batched pages by [ConsentCsvExportService], so heap stays bounded no
 * matter how busy the site is. Deliberately **not** `@Transactional`: a transaction spanning the whole stream
 * would pin one connection for the download's lifetime and defeat that per-page short-transaction design.
 */
@Component
class EvidencePackAssembler(
    private val policyRepository: PolicyRepository,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val consentCsvExportService: ConsentCsvExportService,
    private val objectMapper: ObjectMapper,
) {
    fun write(
        user: UserEntity,
        site: SiteEntity,
        now: Instant,
        out: OutputStream,
    ) {
        val policies = publishedPolicies(site.id)
        val contents = manifestContents(policies)
        ZipOutputStream(out).use { zip ->
            writeJson(zip, MANIFEST_ENTRY, manifest(user, site, now, contents))
            policies.forEach { writeText(zip, policyEntry(it.language), it.html) }
            writeConsentCsv(zip, user.id, site.id, now)
            writeJson(zip, SCAN_REPORT_ENTRY, scanReport(site.id, now))
        }
    }

    /** The full set of entry paths, in write order, so the manifest lists exactly what the pack contains. */
    private fun manifestContents(policies: List<PolicyEntity>): List<String> =
        buildList {
            add(MANIFEST_ENTRY)
            policies.forEach { add(policyEntry(it.language)) }
            add(CONSENT_CSV_ENTRY)
            add(SCAN_REPORT_ENTRY)
        }

    private fun manifest(
        user: UserEntity,
        site: SiteEntity,
        now: Instant,
        contents: List<String>,
    ): EvidenceManifest =
        EvidenceManifest(
            bundledAt = now,
            account = EvidenceAccount(id = user.id, email = user.email, name = user.name),
            site = EvidenceSite(id = site.id, domain = site.domain),
            contents = contents,
            consentEventsWindowDays = CONSENT_WINDOW_DAYS,
            retentionNotice = RETENTION_NOTICE,
        )

    /** Language rows of the current published version, oldest language first for a deterministic pack layout. */
    private fun publishedPolicies(siteId: UUID): List<PolicyEntity> {
        val latest = policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) ?: return emptyList()
        return policyRepository
            .findBySiteIdAndVersion(siteId, latest.version)
            .filter { it.publishedAt != null }
            .sortedBy { it.language }
    }

    private fun scanReport(
        siteId: UUID,
        now: Instant,
    ): EvidenceScanReport {
        val scan = scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) ?: return EvidenceScanReport.EMPTY
        val cookies = scanCookieRepository.findByScanId(scan.id)
        val (classified, unclassified) = cookies.partition { it.isKnown && it.category != null }
        val nonNecessary = classified.filter { it.category != ConsentCategory.NECESSARY.key }
        val trackers = scan.marketingTrackerCount ?: 0
        val report = ComplianceAnalyzer.analyze(cookies, now, trackers)
        return EvidenceScanReport(
            scanId = scan.id,
            scannedAt = scan.finishedAt ?: scan.createdAt,
            totalCookies = cookies.size,
            knownCookies = classified.size,
            unknownCookies = unclassified.size,
            insecureCookies = nonNecessary.count { !it.secure && !it.httpOnly },
            marketingTrackerCount = trackers,
            complianceScore = report.score,
            issues = report.issues,
        )
    }

    /**
     * Stream the trailing-[CONSENT_WINDOW_DAYS] consent log into one ZIP entry, reusing the keyset-batched
     * writer so a high-traffic site's 30 days never has to be held in memory at once. The entry writer is
     * flushed but never closed — closing it would close the shared [ZipOutputStream] mid-pack.
     */
    private fun writeConsentCsv(
        zip: ZipOutputStream,
        userId: UUID,
        siteId: UUID,
        now: Instant,
    ) {
        zip.putNextEntry(ZipEntry(CONSENT_CSV_ENTRY))
        val filter = ConsentLogFilter(from = now.minus(CONSENT_WINDOW))
        val writer = zip.writer(Charsets.UTF_8)
        consentCsvExportService.writeCsv(userId, siteId, filter, writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun writeJson(
        zip: ZipOutputStream,
        entry: String,
        value: Any,
    ) {
        zip.putNextEntry(ZipEntry(entry))
        zip.write(objectMapper.writeValueAsBytes(value))
        zip.closeEntry()
    }

    private fun writeText(
        zip: ZipOutputStream,
        entry: String,
        text: String,
    ) {
        zip.putNextEntry(ZipEntry(entry))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun policyEntry(language: String): String = "policy/$language.html"

    companion object {
        const val CONSENT_WINDOW_DAYS = 30

        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CONSENT_CSV_ENTRY = "consent-events.csv"
        private const val SCAN_REPORT_ENTRY = "scan-report.json"

        private val CONSENT_WINDOW = Duration.ofDays(CONSENT_WINDOW_DAYS.toLong())

        private const val RETENTION_NOTICE =
            "Consent events are append-only audit evidence retained under CookieKeeper's retention policy (ADR-16). " +
                "This pack captures the trailing $CONSENT_WINDOW_DAYS days; the full log is available via the " +
                "consent-log CSV export."
    }
}
