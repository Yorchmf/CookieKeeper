package eu.cookiekeeper.analytics

import eu.cookiekeeper.analytics.dto.EvidenceManifest
import eu.cookiekeeper.analytics.dto.EvidenceScanReport
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.CsvExportNotEntitledException
import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.consent.ConsentCsvExportService
import eu.cookiekeeper.consent.dto.ConsentLogFilter
import eu.cookiekeeper.policy.PolicyEntity
import eu.cookiekeeper.policy.PolicyRepository
import eu.cookiekeeper.scan.ScanCookieEntity
import eu.cookiekeeper.scan.ScanCookieRepository
import eu.cookiekeeper.scan.ScanEntity
import eu.cookiekeeper.scan.ScanRepository
import eu.cookiekeeper.scan.ScanStatus
import eu.cookiekeeper.scan.ScanTrigger
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ComplianceEvidenceService]. The pack is assembled straight into a stream, so the tests
 * drive [ComplianceEvidenceService.prepare] and then run the returned writer into a buffer, unzip it, and
 * assert the entries, the manifest, and the consent window that was requested. Repositories and the consent
 * CSV writer are stubbed so the test exercises only the assembly wiring (structure, gate order, 30-day
 * window, empty-state), not the collaborators' own logic.
 */
class ComplianceEvidenceServiceTest {
    private val userId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-14T09:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val json = JsonMapper.builder().build()

    private val siteRepository = mockk<SiteRepository>()
    private val userRepository = mockk<UserRepository>()
    private val entitlementService = mockk<EntitlementService>(relaxed = true)
    private val policyRepository = mockk<PolicyRepository>()
    private val scanRepository = mockk<ScanRepository>()
    private val scanCookieRepository = mockk<ScanCookieRepository>()
    private val consentCsvExportService = mockk<ConsentCsvExportService>()

    private val service =
        ComplianceEvidenceService(
            siteRepository,
            userRepository,
            entitlementService,
            EvidencePackAssembler(
                policyRepository,
                scanRepository,
                scanCookieRepository,
                consentCsvExportService,
                json,
            ),
            clock,
        )

    private val site = SiteEntity(id = siteId, userId = userId, domain = "shop.example.eu", siteKey = "k")

    private fun stubOwnedSite() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site
        every { userRepository.findById(userId) } returns Optional.of(user(userId))
    }

    private fun user(id: UUID): UserEntity = UserEntity(id = id, email = "owner@example.eu", passwordHash = "x", name = "Owner")

    /** Stub the consent CSV so it writes a recognizable marker, and capture the filter it was handed. */
    private fun stubConsentCsv(): io.mockk.CapturingSlot<ConsentLogFilter> {
        val filterSlot = slot<ConsentLogFilter>()
        every {
            consentCsvExportService.writeCsv(userId, siteId, capture(filterSlot), any())
        } answers {
            arg<Writer>(3).write("action,decided_at\naccept_all,2026-08-01T00:00:00Z\n")
        }
        return filterSlot
    }

    private fun runToZip(prepared: PreparedEvidencePack): Map<String, ByteArray> {
        val buffer = ByteArrayOutputStream()
        prepared.write(buffer)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(buffer.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `entitlement is checked before ownership so a non-Business account never probes the site`() {
        every { entitlementService.requireCsvExport(userId) } throws CsvExportNotEntitledException()

        assertFailsWith<CsvExportNotEntitledException> { service.prepare(userId, siteId) }

        verify(exactly = 0) { siteRepository.findByIdAndUserId(any(), any()) }
    }

    @Test
    fun `a site the caller does not own is a 404, not an empty pack`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns null

        assertFailsWith<SiteNotFoundException> { service.prepare(userId, siteId) }
    }

    @Test
    fun `the filename carries the sanitized domain and the UTC bundle timestamp`() {
        stubOwnedSite()

        val prepared = service.prepare(userId, siteId)

        assertEquals("evidence-pack-shop.example.eu-20260814-093000.zip", prepared.filename)
    }

    @Test
    fun `the pack bundles the manifest, published policies, the consent CSV, and the scan report`() {
        stubOwnedSite()
        val filterSlot = stubConsentCsv()
        // Two languages of the current published version (v3); an older version must be ignored.
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns
            PolicyEntity(siteId = siteId, version = 3, language = "en", html = "<p>en</p>", publishedAt = now)
        every { policyRepository.findBySiteIdAndVersion(siteId, 3) } returns
            listOf(
                PolicyEntity(siteId = siteId, version = 3, language = "en", html = "<p>en</p>", publishedAt = now),
                PolicyEntity(siteId = siteId, version = 3, language = "de", html = "<p>de</p>", publishedAt = now),
            )
        val scan = doneScan()
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns scan
        every { scanCookieRepository.findByScanId(scan.id) } returns
            listOf(cookie("_ga", category = "marketing", known = true, secure = false, httpOnly = false))

        val entries = runToZip(service.prepare(userId, siteId))

        assertContains(entries.keys, "manifest.json")
        assertContains(entries.keys, "policy/en.html")
        assertContains(entries.keys, "policy/de.html")
        assertContains(entries.keys, "consent-events.csv")
        assertContains(entries.keys, "scan-report.json")
        assertEquals("<p>de</p>", entries.getValue("policy/de.html").decodeToString())

        // The consent CSV is the trailing 30 days, and the writer's bytes land in the entry.
        assertEquals(now.minusSeconds(30 * 86_400), filterSlot.captured.from)
        assertNull(filterSlot.captured.to)
        assertContains(entries.getValue("consent-events.csv").decodeToString(), "accept_all")

        val manifest = json.readValue(entries.getValue("manifest.json"), EvidenceManifest::class.java)
        assertEquals(EvidenceManifest.FORMAT, manifest.format)
        assertEquals(now, manifest.bundledAt)
        assertEquals("shop.example.eu", manifest.site.domain)
        assertEquals("owner@example.eu", manifest.account.email)
        assertEquals(30, manifest.consentEventsWindowDays)
        assertTrue(manifest.retentionNotice.isNotBlank())
        assertEquals(
            listOf("manifest.json", "policy/de.html", "policy/en.html", "consent-events.csv", "scan-report.json"),
            manifest.contents,
        )

        val report = json.readValue(entries.getValue("scan-report.json"), EvidenceScanReport::class.java)
        assertEquals(scan.id, report.scanId)
        assertEquals(1, report.totalCookies)
        assertEquals(1, report.knownCookies)
        assertEquals(1, report.insecureCookies)
        assertEquals(2, report.marketingTrackerCount)
    }

    @Test
    fun `a site that has never completed a scan still gets an explicit empty scan report`() {
        stubOwnedSite()
        stubConsentCsv()
        every { policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId) } returns null
        every { scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE) } returns null

        val entries = runToZip(service.prepare(userId, siteId))

        // No published policy → no policy entries, but the report file is always present.
        assertTrue(entries.keys.none { it.startsWith("policy/") })
        val report = json.readValue(entries.getValue("scan-report.json"), EvidenceScanReport::class.java)
        assertNull(report.scanId)
        assertNull(report.complianceScore)
        assertEquals(0, report.totalCookies)
        assertEquals(EvidenceScanReport.EMPTY, report)
    }

    @Test
    fun `an erased account cannot assemble a pack even with a still-valid token`() {
        every { siteRepository.findByIdAndUserId(siteId, userId) } returns site
        every { userRepository.findById(userId) } returns
            Optional.of(user(userId).copy(deletedAt = now))

        // The tombstone is rejected eagerly at prepare time — before any byte could flush.
        assertFailsWith<UnauthenticatedException> { service.prepare(userId, siteId) }
    }

    private fun doneScan(): ScanEntity =
        ScanEntity(
            siteId = siteId,
            status = ScanStatus.DONE,
            trigger = ScanTrigger.MANUAL,
            finishedAt = now.minusSeconds(3600),
            marketingTrackerCount = 2,
            createdAt = now.minusSeconds(4000),
            updatedAt = now.minusSeconds(3600),
        )

    private fun cookie(
        name: String,
        category: String?,
        known: Boolean,
        secure: Boolean,
        httpOnly: Boolean,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = UUID.randomUUID(),
            name = name,
            category = category,
            isKnown = known,
            secure = secure,
            httpOnly = httpOnly,
        )
}
