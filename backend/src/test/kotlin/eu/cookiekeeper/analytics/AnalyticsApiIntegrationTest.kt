package eu.cookiekeeper.analytics

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.billing.SubscriptionEntity
import eu.cookiekeeper.billing.SubscriptionRepository
import eu.cookiekeeper.consent.ConsentEventEntity
import eu.cookiekeeper.consent.ConsentEventRepository
import eu.cookiekeeper.policy.PolicyEntity
import eu.cookiekeeper.policy.PolicyRepository
import eu.cookiekeeper.scan.ScanCookieEntity
import eu.cookiekeeper.scan.ScanCookieRepository
import eu.cookiekeeper.scan.ScanEntity
import eu.cookiekeeper.scan.ScanRepository
import eu.cookiekeeper.scan.ScanStatus
import eu.cookiekeeper.scan.ScanTrigger
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/sites/{siteId}/analytics` (+ `/export.csv`) — the customer analytics read. Covers the
 * authenticated, ownership-scoped envelope, the consent aggregates (action mix, daily trend, per-category
 * opt-in rate, language split), the latest-scan cookie inventory and current policy version, the empty state,
 * the half-open date-range filter, and the Business-only CSV export (403 / 404 / happy path).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AnalyticsApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var consentEventRepository: ConsentEventRepository

    @Autowired private lateinit var scanRepository: ScanRepository

    @Autowired private lateinit var scanCookieRepository: ScanCookieRepository

    @Autowired private lateinit var policyRepository: PolicyRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository

    private data class Account(
        val cookie: Cookie,
        val id: UUID,
    )

    private fun registeredUser(): Account {
        val email = "user-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"en"}"""),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"${emailSender.lastTokenFor(email)}"}"""),
            ).andExpect(status().isOk)
        val login =
            mockMvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"s3cret-password"}"""),
                ).andExpect(status().isOk)
                .andReturn()
        val accessHeader =
            assertNotNull(login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") })
        val userId = assertNotNull(userRepository.findByEmail(email)).id
        return Account(Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";")), userId)
    }

    private fun businessUser(): Account {
        val account = registeredUser()
        val now = Instant.parse("2026-08-01T00:00:00Z")
        subscriptionRepository.save(
            SubscriptionEntity(
                userId = account.id,
                stripeCustomerId = "cus_${UUID.randomUUID()}",
                stripeSubId = "sub_${UUID.randomUUID()}",
                plan = Plan.BUSINESS,
                status = "active",
                periodEnd = now.plusSeconds(2_592_000),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return account
    }

    private fun createSite(
        cookie: Cookie,
        domain: String,
    ): UUID {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/sites")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"domain":"$domain"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        return UUID.fromString(
            objectMapper
                .readTree(result.response.contentAsString)
                .path("data")
                .path("id")
                .asString(),
        )
    }

    private fun seedEvent(
        siteId: UUID,
        createdAt: Instant,
        action: String,
        categories: Map<String, Boolean>,
        lang: String?,
    ) {
        consentEventRepository.save(
            ConsentEventEntity(
                siteId = siteId,
                visitorId = UUID.randomUUID(),
                action = action,
                categories = categories,
                lang = lang,
                ipHash = "hash-${UUID.randomUUID()}",
                ua = "test-agent",
                createdAt = createdAt,
            ),
        )
    }

    private fun seedCompletedScan(
        siteId: UUID,
        cookies: List<ScanCookieEntity>,
        trackerCount: Int,
    ): UUID {
        val now = Instant.parse("2026-08-09T00:00:00Z")
        val scan =
            scanRepository.save(
                ScanEntity(
                    siteId = siteId,
                    status = ScanStatus.DONE,
                    trigger = ScanTrigger.MANUAL,
                    startedAt = now,
                    finishedAt = now.plusSeconds(30),
                    pagesCrawled = 3,
                    marketingTrackerCount = trackerCount,
                    createdAt = now,
                    updatedAt = now.plusSeconds(30),
                ),
            )
        scanCookieRepository.saveAll(cookies.map { it.copy(scanId = scan.id) })
        return scan.id
    }

    private fun cookie(
        name: String,
        category: String?,
        isKnown: Boolean,
        secure: Boolean = true,
        httpOnly: Boolean = true,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = UUID.randomUUID(), // replaced in seedCompletedScan
            name = name,
            category = category,
            isKnown = isKnown,
            secure = secure,
            httpOnly = httpOnly,
        )

    private fun uniqueDomain(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}.example.com"

    @Test
    fun `aggregates consent, cookie inventory and policy for the owning user`() {
        val alice = registeredUser()
        val siteId = createSite(alice.cookie, uniqueDomain("analytics"))
        val day1 = Instant.parse("2026-08-05T10:00:00Z")
        val day2 = Instant.parse("2026-08-06T10:00:00Z")
        // 4 events: 2 accept_all, 1 reject_all, 1 custom. statistics true on 3, marketing true on 1.
        seedEvent(siteId, day1, "accept_all", mapOf("statistics" to true, "marketing" to true), "en")
        seedEvent(siteId, day1, "accept_all", mapOf("statistics" to true, "marketing" to false), "de")
        seedEvent(siteId, day2, "reject_all", mapOf("statistics" to false, "marketing" to false), "en")
        seedEvent(siteId, day2, "custom", mapOf("statistics" to true, "marketing" to false), "fr")
        seedCompletedScan(
            siteId,
            cookies =
                listOf(
                    cookie("sid", "necessary", isKnown = true),
                    cookie("_ga", "statistics", isKnown = true, secure = false, httpOnly = false),
                    cookie("_fbp", "marketing", isKnown = true),
                    cookie("mystery", null, isKnown = false),
                ),
            trackerCount = 2,
        )
        policyRepository.save(PolicyEntity(siteId = siteId, version = 3, language = "en", html = "<p>en</p>", publishedAt = day2))
        policyRepository.save(PolicyEntity(siteId = siteId, version = 3, language = "de", html = "<p>de</p>", publishedAt = day2))

        mockMvc
            .perform(
                get("/api/v1/sites/$siteId/analytics")
                    .param("from", "2026-08-01T00:00:00Z")
                    .param("to", "2026-08-10T00:00:00Z")
                    .cookie(alice.cookie),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consent.totalEvents").value(4))
            .andExpect(jsonPath("$.data.consent.byAction.acceptAll").value(2))
            .andExpect(jsonPath("$.data.consent.byAction.rejectAll").value(1))
            .andExpect(jsonPath("$.data.consent.byAction.custom").value(1))
            // Two distinct UTC days in the trend, oldest-first.
            .andExpect(jsonPath("$.data.consent.trend.length()").value(2))
            .andExpect(jsonPath("$.data.consent.trend[0].date").value("2026-08-05"))
            .andExpect(jsonPath("$.data.consent.trend[0].acceptAll").value(2))
            .andExpect(jsonPath("$.data.consent.trend[1].total").value(2))
            // statistics opted in on 3 of 4 decisions.
            .andExpect(jsonPath("$.data.consent.categoryOptIn[?(@.category == 'statistics')].optIns").value(3))
            .andExpect(jsonPath("$.data.consent.categoryOptIn[?(@.category == 'statistics')].decisions").value(4))
            .andExpect(jsonPath("$.data.consent.categoryOptIn[?(@.category == 'marketing')].optIns").value(1))
            // language split: en appears twice.
            .andExpect(jsonPath("$.data.consent.languageSplit[?(@.lang == 'en')].count").value(2))
            // cookie inventory from the completed scan.
            .andExpect(jsonPath("$.data.cookies.total").value(4))
            .andExpect(jsonPath("$.data.cookies.known").value(3))
            .andExpect(jsonPath("$.data.cookies.unknown").value(1))
            .andExpect(jsonPath("$.data.cookies.insecure").value(1))
            .andExpect(jsonPath("$.data.cookies.trackerCount").value(2))
            // policy: current published version and its languages.
            .andExpect(jsonPath("$.data.policy.version").value(3))
            .andExpect(jsonPath("$.data.policy.languages.length()").value(2))
    }

    @Test
    fun `returns zeroed consent and null cookie or policy for a site with no data`() {
        val alice = registeredUser()
        val siteId = createSite(alice.cookie, uniqueDomain("empty"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics").cookie(alice.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consent.totalEvents").value(0))
            .andExpect(jsonPath("$.data.consent.trend.length()").value(0))
            .andExpect(jsonPath("$.data.cookies").doesNotExist())
            .andExpect(jsonPath("$.data.policy").doesNotExist())
    }

    @Test
    fun `honours the half-open date range (from inclusive, to exclusive)`() {
        val alice = registeredUser()
        val siteId = createSite(alice.cookie, uniqueDomain("range"))
        seedEvent(siteId, Instant.parse("2026-08-15T00:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // before
        seedEvent(siteId, Instant.parse("2026-08-16T12:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // inside
        seedEvent(siteId, Instant.parse("2026-08-17T00:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // == to, excluded

        mockMvc
            .perform(
                get("/api/v1/sites/$siteId/analytics")
                    .param("from", "2026-08-16T00:00:00Z")
                    .param("to", "2026-08-17T00:00:00Z")
                    .cookie(alice.cookie),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consent.totalEvents").value(1))
    }

    @Test
    fun `another user cannot read analytics for a site they do not own`() {
        val alice = registeredUser()
        val siteId = createSite(alice.cookie, uniqueDomain("owned"))
        val bob = registeredUser()

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics").cookie(bob.cookie))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `anonymous request is rejected with 401`() {
        val alice = registeredUser()
        val siteId = createSite(alice.cookie, uniqueDomain("anon"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `business account exports the consent trend as csv`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("export"))
        seedEvent(siteId, Instant.parse("2026-08-05T10:00:00Z"), "accept_all", mapOf("statistics" to true), "en")
        seedEvent(siteId, Instant.parse("2026-08-05T11:00:00Z"), "reject_all", mapOf("statistics" to false), "en")

        val csv =
            mockMvc
                .perform(
                    get("/api/v1/sites/$siteId/analytics/export.csv")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-10T00:00:00Z")
                        .cookie(account.cookie),
                ).andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertTrue(csv.startsWith("date,accept_all,reject_all,custom,total\r\n"))
        assertTrue(csv.contains("2026-08-05,1,1,0,2\r\n"), "expected the day's trend row; got:\n$csv")
    }

    @Test
    fun `non-business account is denied the csv export with 403`() {
        val account = registeredUser() // trial: csvExport = false
        val siteId = createSite(account.cookie, uniqueDomain("trial"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/export.csv").cookie(account.cookie))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CSV_EXPORT_NOT_ENTITLED"))
    }

    @Test
    fun `entitled user cannot export analytics for a site they do not own`() {
        val owner = registeredUser()
        val siteId = createSite(owner.cookie, uniqueDomain("owned"))
        val intruder = businessUser()

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/export.csv").cookie(intruder.cookie))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `csv export never leaks the non-existent pii columns`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("clean"))
        seedEvent(siteId, Instant.parse("2026-08-05T10:00:00Z"), "accept_all", mapOf("statistics" to true), "en")

        val csv =
            mockMvc
                .perform(get("/api/v1/sites/$siteId/analytics/export.csv").cookie(account.cookie))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        assertFalse(csv.contains("hash-"), "ip hash must never reach an aggregate export")
        assertFalse(csv.contains("test-agent"), "user agent must never reach an aggregate export")
    }

    /** Drives a streaming (async) GET to completion and returns the ZIP entry name → bytes map. */
    private fun downloadEvidencePack(
        siteId: UUID,
        cookie: Cookie,
    ): Pair<String, Map<String, ByteArray>> {
        val started =
            mockMvc
                .perform(get("/api/v1/sites/$siteId/analytics/evidence-pack.zip").cookie(cookie))
                .andExpect(status().isOk)
                .andReturn()
        val response =
            mockMvc
                .perform(asyncDispatch(started))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn()
                .response
        val disposition = assertNotNull(response.getHeader("Content-Disposition"))
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(response.contentAsByteArray.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return disposition to entries
    }

    @Test
    fun `business account downloads a compliance evidence pack zip`() {
        val account = businessUser()
        val domain = uniqueDomain("evidence")
        val siteId = createSite(account.cookie, domain)
        seedEvent(siteId, Instant.parse("2026-08-05T10:00:00Z"), "accept_all", mapOf("statistics" to true), "en")
        seedCompletedScan(
            siteId,
            cookies = listOf(cookie("_ga", "statistics", isKnown = true, secure = false, httpOnly = false)),
            trackerCount = 2,
        )
        val publishedAt = Instant.parse("2026-08-04T00:00:00Z")
        policyRepository.save(PolicyEntity(siteId = siteId, version = 3, language = "en", html = "<p>en</p>", publishedAt = publishedAt))

        val (disposition, entries) = downloadEvidencePack(siteId, account.cookie)

        assertTrue(disposition.contains("evidence-pack-$domain-"), "filename should carry the domain; got: $disposition")
        assertTrue(disposition.endsWith(".zip\""))
        assertEquals(
            setOf("manifest.json", "policy/en.html", "consent-events.csv", "scan-report.json"),
            entries.keys,
        )
        // The manifest is a self-describing English document naming the site and the 30-day consent window.
        val manifest = objectMapper.readTree(entries.getValue("manifest.json"))
        assertEquals(domain, manifest.path("site").path("domain").asString())
        assertEquals(30, manifest.path("consentEventsWindowDays").asInt())
        // The embedded consent CSV carries the real audit header, and the scan report the real score.
        assertTrue(entries.getValue("consent-events.csv").decodeToString().startsWith("created_at,"))
        assertTrue(objectMapper.readTree(entries.getValue("scan-report.json")).path("complianceScore").isNumber)
    }

    @Test
    fun `non-business account is denied the evidence pack with 403`() {
        val account = registeredUser() // trial: csvExport = false
        val siteId = createSite(account.cookie, uniqueDomain("trial-pack"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/evidence-pack.zip").cookie(account.cookie))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CSV_EXPORT_NOT_ENTITLED"))
    }

    @Test
    fun `entitled user cannot download an evidence pack for a site they do not own`() {
        val owner = registeredUser()
        val siteId = createSite(owner.cookie, uniqueDomain("owned-pack"))
        val intruder = businessUser()

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/evidence-pack.zip").cookie(intruder.cookie))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `anonymous request for an evidence pack is rejected with 401`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("anon-pack"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/evidence-pack.zip"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `an erased account cannot download an evidence pack with a still-valid token`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("erased-pack"))
        // Art. 17 tombstone (ADR-20): the row survives to anchor consent-bearing sites, but ErasedAccountFilter
        // must block every authenticated request before the controller — the still-valid access cookie above
        // must not stream a single byte of the (Business-gated, PII-bearing) pack.
        val user = assertNotNull(userRepository.findById(account.id).orElse(null))
        userRepository.save(user.copy(deletedAt = Instant.parse("2026-08-10T00:00:00Z")))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/analytics/evidence-pack.zip").cookie(account.cookie))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
