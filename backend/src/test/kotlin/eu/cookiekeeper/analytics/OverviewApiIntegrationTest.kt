package eu.cookiekeeper.analytics

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.banner.BannerConfigEntity
import eu.cookiekeeper.banner.BannerConfigRepository
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
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.VerificationMethod
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
 * `GET /api/v1/overview` — the account roll-up behind the dashboard home. Covers the authenticated envelope,
 * the cross-site headline figures, the severity-ordered action list, the empty state of a brand-new account,
 * and the account boundary (another user's sites must not leak into either).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class OverviewApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var consentEventRepository: ConsentEventRepository

    @Autowired private lateinit var scanRepository: ScanRepository

    @Autowired private lateinit var scanCookieRepository: ScanCookieRepository

    @Autowired private lateinit var policyRepository: PolicyRepository

    @Autowired private lateinit var siteRepository: SiteRepository

    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired private lateinit var bannerConfigRepository: BannerConfigRepository

    private data class Account(
        val cookie: Cookie,
        val id: UUID,
    )

    private fun registeredUser(plan: Plan? = null): Account {
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
        if (plan != null) grantSubscription(userId, plan)
        return Account(Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";")), userId)
    }

    // The overview roll-ups need several sites; a fresh account is on Trial (1-site cap), so the
    // multi-site scenarios first grant a paid plan. This keeps createSite going through the real
    // POST /api/v1/sites path rather than seeding the site rows directly.
    private fun grantSubscription(
        userId: UUID,
        plan: Plan,
    ) {
        val now = SUBSCRIBED_AT
        subscriptionRepository.saveAndFlush(
            SubscriptionEntity(
                userId = userId,
                stripeCustomerId = "cus_${UUID.randomUUID()}",
                stripeSubId = "sub_${UUID.randomUUID()}",
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
        verified: Boolean = true,
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
        val id =
            UUID.fromString(
                objectMapper
                    .readTree(result.response.contentAsString)
                    .path("data")
                    .path("id")
                    .asString(),
            )
        // Verification is an out-of-band proof (ADR-17); stamping the row directly keeps this test about the
        // overview rather than about DNS/snippet fetching.
        if (verified) {
            val site = assertNotNull(siteRepository.findById(id).orElse(null))
            siteRepository.save(site.copy(verifiedAt = VERIFIED_AT, verificationMethod = VerificationMethod.SNIPPET))
        }
        return id
    }

    private fun seedEvent(
        siteId: UUID,
        action: String,
    ) {
        consentEventRepository.save(
            ConsentEventEntity(
                siteId = siteId,
                visitorId = UUID.randomUUID(),
                action = action,
                categories = mapOf("statistics" to (action == "accept_all")),
                lang = "en",
                ipHash = "hash-${UUID.randomUUID()}",
                ua = "test-agent",
                createdAt = EVENT_AT,
            ),
        )
    }

    private fun seedCompletedScan(
        siteId: UUID,
        cookies: List<ScanCookieEntity>,
        scannedAt: Instant = SCANNED_AT,
    ) {
        val scan =
            scanRepository.save(
                ScanEntity(
                    siteId = siteId,
                    status = ScanStatus.DONE,
                    trigger = ScanTrigger.MANUAL,
                    startedAt = scannedAt,
                    finishedAt = scannedAt,
                    pagesCrawled = 3,
                    marketingTrackerCount = 1,
                    createdAt = scannedAt,
                    updatedAt = scannedAt,
                ),
            )
        scanCookieRepository.saveAll(cookies.map { it.copy(scanId = scan.id) })
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

    // Appends a banner version past the seeded v1 to mark the site as "customised". Reuses the seed's
    // document so the test doesn't have to build a valid config — only the version bump matters here.
    private fun customiseBanner(siteId: UUID) {
        val seed = assertNotNull(bannerConfigRepository.findFirstBySiteIdOrderByVersionDesc(siteId))
        bannerConfigRepository.save(
            BannerConfigEntity(siteId = siteId, version = seed.version + 1, config = seed.config, publishedAt = PUBLISHED_AT),
        )
    }

    private fun uniqueDomain(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}.example.com"

    private fun overview(cookie: Cookie) =
        mockMvc.perform(
            get("/api/v1/overview")
                .param("from", "2026-08-01T00:00:00Z")
                .param("to", "2026-08-10T00:00:00Z")
                .cookie(cookie),
        )

    @Test
    fun `requires authentication`() {
        mockMvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a brand-new account gets zeroed figures and no actions`() {
        val account = registeredUser()

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.headline.activeSites").value(0))
            .andExpect(jsonPath("$.data.headline.consentEvents").value(0))
            // Absent, not 0.0 — Jackson drops nulls, and "no data" must not render as "0% accepted".
            .andExpect(jsonPath("$.data.headline.acceptAllRate").doesNotExist())
            .andExpect(jsonPath("$.data.headline.lastScanAt").doesNotExist())
            .andExpect(jsonPath("$.data.actions.length()").value(0))
            // No sites yet — every onboarding step is still to do.
            .andExpect(jsonPath("$.data.onboarding.addedSite").value(false))
            .andExpect(jsonPath("$.data.onboarding.scanned").value(false))
            .andExpect(jsonPath("$.data.onboarding.customisedBanner").value(false))
            .andExpect(jsonPath("$.data.onboarding.verified").value(false))
    }

    @Test
    fun `onboarding reflects add, scan, customise and verify against the real data`() {
        val account = registeredUser(Plan.BUSINESS)
        // One site carries the account through scan + verify; another carries the customise step, so the
        // flags exercise the account-wide "any site" rule end to end.
        val live = createSite(account.cookie, uniqueDomain("live"))
        seedCompletedScan(live, listOf(cookie("sid", "necessary", isKnown = true)))
        val customised = createSite(account.cookie, uniqueDomain("customised"), verified = false)
        customiseBanner(customised)

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.onboarding.addedSite").value(true))
            .andExpect(jsonPath("$.data.onboarding.scanned").value(true))
            // max(version) > 1 for the second site: the SQL rollup, not just the unit-tested threshold.
            .andExpect(jsonPath("$.data.onboarding.customisedBanner").value(true))
            .andExpect(jsonPath("$.data.onboarding.verified").value(true))
    }

    @Test
    fun `rolls up consent, cookies and scan recency across every site the account owns`() {
        val account = registeredUser(Plan.BUSINESS)
        val first = createSite(account.cookie, uniqueDomain("roll-a"))
        val second = createSite(account.cookie, uniqueDomain("roll-b"))
        repeat(3) { seedEvent(first, "accept_all") }
        seedEvent(second, "reject_all")
        seedEvent(second, "custom")
        seedCompletedScan(first, listOf(cookie("sid", "necessary", isKnown = true)))
        seedCompletedScan(
            second,
            listOf(cookie("_ga", "statistics", isKnown = true), cookie("mystery", null, isKnown = false)),
            scannedAt = SCANNED_AT.plusSeconds(3_600),
        )
        listOf(first, second).forEach {
            policyRepository.save(
                PolicyEntity(siteId = it, version = 1, language = "en", html = "<p>en</p>", publishedAt = PUBLISHED_AT),
            )
        }

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.headline.activeSites").value(2))
            .andExpect(jsonPath("$.data.headline.consentEvents").value(5))
            // 3 accept_all out of 5 decisions — custom counts as a decision, never as an acceptance.
            .andExpect(jsonPath("$.data.headline.acceptAllRate").value(0.6))
            .andExpect(jsonPath("$.data.headline.cookiesFound").value(3))
            // The most recent scan across all sites, not the first one found.
            .andExpect(jsonPath("$.data.headline.lastScanAt").value(SCANNED_AT.plusSeconds(3_600).toString()))
            .andExpect(jsonPath("$.data.actions.length()").value(0))
            // Both sites are added, scanned and verified, but neither edited its seeded v1 banner.
            .andExpect(jsonPath("$.data.onboarding.customisedBanner").value(false))
            .andExpect(jsonPath("$.data.onboarding.verified").value(true))
    }

    @Test
    fun `reports one action per site, most severe first`() {
        val account = registeredUser(Plan.BUSINESS)
        val unverified = createSite(account.cookie, uniqueDomain("unverified"), verified = false)
        val unscanned = createSite(account.cookie, uniqueDomain("unscanned"))
        val insecure = createSite(account.cookie, uniqueDomain("insecure"))
        seedCompletedScan(
            insecure,
            listOf(
                cookie("_ga", "statistics", isKnown = true, secure = false, httpOnly = false),
                cookie("_fbp", "marketing", isKnown = true, secure = false, httpOnly = false),
            ),
        )
        policyRepository.save(
            PolicyEntity(siteId = insecure, version = 1, language = "en", html = "<p>en</p>", publishedAt = PUBLISHED_AT),
        )

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.actions.length()").value(3))
            .andExpect(jsonPath("$.data.actions[0].kind").value("unverified"))
            .andExpect(jsonPath("$.data.actions[0].siteId").value(unverified.toString()))
            .andExpect(jsonPath("$.data.actions[1].kind").value("never_scanned"))
            .andExpect(jsonPath("$.data.actions[1].siteId").value(unscanned.toString()))
            .andExpect(jsonPath("$.data.actions[2].kind").value("insecure_cookies"))
            .andExpect(jsonPath("$.data.actions[2].siteId").value(insecure.toString()))
            .andExpect(jsonPath("$.data.actions[2].count").value(2))
    }

    @Test
    fun `a published policy older than the latest scan reads as stale`() {
        val account = registeredUser()
        val siteId = createSite(account.cookie, uniqueDomain("stale"))
        seedCompletedScan(siteId, listOf(cookie("sid", "necessary", isKnown = true)))
        // Published BEFORE the crawl, so it cannot describe what the crawl found.
        policyRepository.save(
            PolicyEntity(
                siteId = siteId,
                version = 1,
                language = "en",
                html = "<p>en</p>",
                publishedAt = SCANNED_AT.minusSeconds(86_400),
            ),
        )

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.actions.length()").value(1))
            .andExpect(jsonPath("$.data.actions[0].kind").value("policy_stale"))
    }

    @Test
    fun `an unpublished draft policy still reads as missing`() {
        val account = registeredUser()
        val siteId = createSite(account.cookie, uniqueDomain("draft"))
        seedCompletedScan(siteId, listOf(cookie("sid", "necessary", isKnown = true)))
        policyRepository.save(PolicyEntity(siteId = siteId, version = 1, language = "en", html = "<p>en</p>", publishedAt = null))

        overview(account.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.actions[0].kind").value("policy_missing"))
    }

    @Test
    fun `another account's sites and events are invisible`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val bobSite = createSite(bob.cookie, uniqueDomain("bob"), verified = false)
        repeat(4) { seedEvent(bobSite, "accept_all") }

        overview(alice.cookie)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.headline.activeSites").value(0))
            .andExpect(jsonPath("$.data.headline.consentEvents").value(0))
            .andExpect(jsonPath("$.data.actions.length()").value(0))
    }

    private companion object {
        val SUBSCRIBED_AT: Instant = Instant.parse("2026-08-01T00:00:00Z")
        val VERIFIED_AT: Instant = Instant.parse("2026-08-02T00:00:00Z")
        val EVENT_AT: Instant = Instant.parse("2026-08-05T10:00:00Z")
        val SCANNED_AT: Instant = Instant.parse("2026-08-06T00:00:00Z")
        val PUBLISHED_AT: Instant = Instant.parse("2026-08-07T00:00:00Z")
    }
}
