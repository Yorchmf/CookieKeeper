package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.billing.SubscriptionEntity
import eu.cookiekeeper.billing.SubscriptionRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * Scan-results read API: cookie grouping in the detail view, ownership isolation (user B sees 404 on
 * user A's scans), cross-site scoping (a scan is unreachable via the wrong site), and the not-found /
 * unauthenticated envelopes. Scans are seeded directly since the Playwright crawler is `scanner`-profile
 * only and does not run in this context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ScanApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var scanRepository: ScanRepository

    @Autowired
    private lateinit var scanCookieRepository: ScanCookieRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var subscriptionRepository: SubscriptionRepository

    /**
     * Register + verify + log in a fresh user. [plan], when set, provisions an active subscription so
     * the account clears the plan site-cap (a fresh trial account only allows one site) — needed by the
     * multi-site scoping test.
     */
    private fun registeredUser(plan: Plan? = null): Cookie {
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
        if (plan != null) grantSubscription(email, plan)
        return Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";"))
    }

    /** Give the just-registered user an active paid subscription so its plan site-cap allows multiple sites. */
    private fun grantSubscription(
        email: String,
        plan: Plan,
    ) {
        val userId = requireNotNull(userRepository.findByEmail(email)).id
        val now = Instant.now()
        subscriptionRepository.saveAndFlush(
            SubscriptionEntity(
                userId = userId,
                stripeCustomerId = null,
                stripeSubId = null,
                plan = plan,
                status = "active",
                periodEnd = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
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

    /**
     * Insert a completed scan for [siteId]. By default it carries a mix of classified + unknown cookies;
     * pass [withCookies] = false for the empty-scan shape. [createdAt] is explicit so ordering tests can
     * bracket their scans deterministically above any scan the site-creation flow may enqueue.
     */
    private fun seedCompletedScan(
        siteId: UUID,
        createdAt: Instant = Instant.now(),
        withCookies: Boolean = true,
    ): UUID {
        val scan =
            scanRepository.save(
                ScanEntity(
                    siteId = siteId,
                    status = ScanStatus.DONE,
                    trigger = ScanTrigger.MANUAL,
                    startedAt = createdAt,
                    finishedAt = createdAt,
                    pagesCrawled = 3,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
        if (withCookies) {
            scanCookieRepository.saveAll(
                listOf(
                    classified(scan.id, "_ga", "statistics", "Google Analytics"),
                    classified(scan.id, "_gid", "statistics", "Google Analytics"),
                    classified(scan.id, "PHPSESSID", "necessary", "PHP"),
                    ScanCookieEntity(scanId = scan.id, name = "mystery_cookie", isKnown = false),
                ),
            )
        }
        return scan.id
    }

    private fun classified(
        scanId: UUID,
        name: String,
        category: String,
        provider: String,
    ): ScanCookieEntity = ScanCookieEntity(scanId = scanId, name = name, category = category, provider = provider, isKnown = true)

    @Test
    fun `scan detail groups classified cookies by category and buckets the unknown for review`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "grouping-${UUID.randomUUID().toString().take(8)}.example.com")
        val scanId = seedCompletedScan(siteId)

        // History: the seeded DONE scan is newest, so it heads the list.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value(scanId.toString()))
            .andExpect(jsonPath("$.data[0].status").value("done"))
            .andExpect(jsonPath("$.data[0].pagesCrawled").value(3))

        // Detail: two statistics cookies grouped, one necessary, and the unknown in needsReview.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans/$scanId").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.cookiesByCategory.statistics.length()").value(2))
            .andExpect(jsonPath("$.data.cookiesByCategory.necessary.length()").value(1))
            .andExpect(jsonPath("$.data.cookiesByCategory.necessary[0].provider").value("PHP"))
            .andExpect(jsonPath("$.data.needsReview.length()").value(1))
            .andExpect(jsonPath("$.data.needsReview[0].name").value("mystery_cookie"))
            .andExpect(jsonPath("$.data.needsReview[0].isKnown").value(false))
    }

    @Test
    fun `scan history is newest-first and honours the limit bound`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "history-${UUID.randomUUID().toString().take(8)}.example.com")
        // Bracket both scans in the future so they sort above any scan the site-creation flow enqueues.
        val base = Instant.now().plusSeconds(3600)
        val older = seedCompletedScan(siteId, createdAt = base)
        val newer = seedCompletedScan(siteId, createdAt = base.plusSeconds(60))

        // Full history: newest scan first, then the older one.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].id").value(newer.toString()))
            .andExpect(jsonPath("$.data[1].id").value(older.toString()))

        // limit=1 returns only the newest, and meta.total reflects the returned page size.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans").param("limit", "1").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(newer.toString()))
            .andExpect(jsonPath("$.meta.total").value(1))
    }

    @Test
    fun `a scan with no cookies yields empty groupings`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "empty-${UUID.randomUUID().toString().take(8)}.example.com")
        val scanId = seedCompletedScan(siteId, withCookies = false)

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans/$scanId").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.cookiesByCategory").isEmpty)
            .andExpect(jsonPath("$.data.needsReview").isEmpty)
    }

    @Test
    fun `user B cannot read user A's scan history or detail`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val siteId = createSite(alice, "alice-${UUID.randomUUID().toString().take(8)}.example.com")
        val scanId = seedCompletedScan(siteId)

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans/$scanId").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `a scan is not reachable through a sibling site the caller also owns`() {
        // BUSINESS plan: the scoping test needs two owned sites, which a trial account's cap forbids.
        val alice = registeredUser(Plan.BUSINESS)
        val siteA = createSite(alice, "site-a-${UUID.randomUUID().toString().take(8)}.example.com")
        val siteB = createSite(alice, "site-b-${UUID.randomUUID().toString().take(8)}.example.com")
        val scanUnderB = seedCompletedScan(siteB)

        // Owned site A, but the scan belongs to B → scoped miss, not a leak.
        mockMvc
            .perform(get("/api/v1/sites/$siteA/scans/$scanUnderB").cookie(alice))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SCAN_NOT_FOUND"))
    }

    @Test
    fun `unknown scan id under an owned site returns 404 SCAN_NOT_FOUND`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "missing-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scans/${UUID.randomUUID()}").cookie(alice))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SCAN_NOT_FOUND"))
    }

    @Test
    fun `scan endpoints require authentication`() {
        mockMvc
            .perform(get("/api/v1/sites/${UUID.randomUUID()}/scans"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        // The schedule read sits on the same prefix but a different suffix; assert it at the HTTP layer
        // too, so a future SecurityConfig matcher that exempts it can't pass on the service unit tests.
        mockMvc
            .perform(get("/api/v1/sites/${UUID.randomUUID()}/scan-schedule"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `a paid plan's schedule names the next scan date, and user B cannot read it`() {
        val alice = registeredUser(Plan.PRO)
        val bob = registeredUser()
        // Creating the site enqueues its first scan, so the site has a "last scan" to count forward from.
        val siteId = createSite(alice, "schedule-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scan-schedule").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scheduled").value(true))
            .andExpect(jsonPath("$.data.frequency").value("weekly"))
            .andExpect(jsonPath("$.data.nextScanAt").isNotEmpty)
            .andExpect(jsonPath("$.data.reason").isEmpty)

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scan-schedule").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `a trial account is told its trial ends first rather than a date past it`() {
        // No subscription: a 14-day trial on Starter's monthly cadence, so the first cycle after the
        // site's initial scan falls due roughly two weeks after the account has already lapsed.
        val alice = registeredUser()
        val siteId = createSite(alice, "trial-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/scan-schedule").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.scheduled").value(false))
            .andExpect(jsonPath("$.data.reason").value("trial_ends_first"))
            .andExpect(jsonPath("$.data.nextScanAt").isEmpty)
    }
}
