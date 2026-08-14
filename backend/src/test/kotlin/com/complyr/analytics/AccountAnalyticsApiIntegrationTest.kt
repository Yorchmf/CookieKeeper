package com.complyr.analytics

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.auth.UserRepository
import com.complyr.billing.Plan
import com.complyr.billing.SubscriptionEntity
import com.complyr.billing.SubscriptionRepository
import com.complyr.consent.ConsentEventEntity
import com.complyr.consent.ConsentEventRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * `GET /api/v1/analytics/accounts/rollup` — the cross-site ("All Sites") consent roll-up. Covers the
 * JWT-scoped ownership (no id in the path, the account is the principal), the Pro/Business entitlement gate
 * (Trial and Starter are single-site → 403), the aggregation across every ACTIVE site the account owns
 * (archived sites excluded), the half-open date-range filter, and cross-account isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class AccountAnalyticsApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var consentEventRepository: ConsentEventRepository

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

    private fun subscribedUser(plan: Plan): Account {
        val account = registeredUser()
        val now = Instant.parse("2026-08-01T00:00:00Z")
        subscriptionRepository.save(
            SubscriptionEntity(
                userId = account.id,
                stripeCustomerId = "cus_${UUID.randomUUID()}",
                stripeSubId = "sub_${UUID.randomUUID()}",
                plan = plan,
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

    private fun uniqueDomain(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}.example.com"

    @Test
    fun `anonymous request is rejected with 401`() {
        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `trial (starter-shaped) account is denied the roll-up with 403`() {
        val account = registeredUser() // trial: crossSiteAnalytics = false

        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup").cookie(account.cookie))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CROSS_SITE_ANALYTICS_NOT_ENTITLED"))
    }

    @Test
    fun `starter account is denied the roll-up with 403`() {
        val account = subscribedUser(Plan.STARTER)

        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup").cookie(account.cookie))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CROSS_SITE_ANALYTICS_NOT_ENTITLED"))
    }

    @Test
    fun `pro account rolls up consent across every active site`() {
        val account = subscribedUser(Plan.PRO)
        val siteA = createSite(account.cookie, uniqueDomain("rollup-a"))
        val siteB = createSite(account.cookie, uniqueDomain("rollup-b"))
        val day1 = Instant.parse("2026-08-05T10:00:00Z")
        val day2 = Instant.parse("2026-08-06T10:00:00Z")
        // Site A: 2 accept_all (day1), 1 reject_all (day2). Site B: 1 custom (day1).
        seedEvent(siteA, day1, "accept_all", mapOf("statistics" to true, "marketing" to true), "en")
        seedEvent(siteA, day1, "accept_all", mapOf("statistics" to true, "marketing" to false), "de")
        seedEvent(siteA, day2, "reject_all", mapOf("statistics" to false, "marketing" to false), "en")
        seedEvent(siteB, day1, "custom", mapOf("statistics" to true, "marketing" to false), "fr")

        val response =
            mockMvc
                .perform(
                    get("/api/v1/analytics/accounts/rollup")
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-10T00:00:00Z")
                        .cookie(account.cookie),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.siteCount").value(2))
                .andExpect(jsonPath("$.data.consent.totalEvents").value(4))
                .andExpect(jsonPath("$.data.consent.byAction.acceptAll").value(2))
                .andExpect(jsonPath("$.data.consent.byAction.rejectAll").value(1))
                .andExpect(jsonPath("$.data.consent.byAction.custom").value(1))
                // day1 (3 events across both sites) then day2 (1), oldest-first.
                .andExpect(jsonPath("$.data.consent.trend.length()").value(2))
                .andExpect(jsonPath("$.data.consent.trend[0].date").value("2026-08-05"))
                .andExpect(jsonPath("$.data.consent.trend[0].total").value(3))
                .andExpect(jsonPath("$.data.consent.trend[1].total").value(1))
                // statistics opted in on 3 of 4 decisions, merged across sites.
                .andExpect(jsonPath("$.data.consent.categoryOptIn[?(@.category == 'statistics')].optIns").value(3))
                .andExpect(jsonPath("$.data.consent.categoryOptIn[?(@.category == 'statistics')].decisions").value(4))
                // language split merged across sites: en appears twice.
                .andExpect(jsonPath("$.data.consent.languageSplit[?(@.lang == 'en')].count").value(2))
                .andReturn()

        // No visitor PII (ip hash, user agent) may ever reach the aggregate response — the roll-up projects
        // only counts, categories, and language. Locks the no-leak invariant against a future field regression.
        val body = response.response.contentAsString
        assert(!body.contains("test-agent")) { "user agent leaked into roll-up response" }
        assert(!body.contains("hash-")) { "ip hash leaked into roll-up response" }
    }

    @Test
    fun `excludes archived sites from the roll-up`() {
        val account = subscribedUser(Plan.PRO)
        val active = createSite(account.cookie, uniqueDomain("rollup-active"))
        val archived = createSite(account.cookie, uniqueDomain("rollup-archived"))
        val day = Instant.parse("2026-08-05T10:00:00Z")
        seedEvent(active, day, "accept_all", mapOf("statistics" to true), "en")
        seedEvent(archived, day, "reject_all", mapOf("statistics" to false), "en")
        mockMvc
            .perform(delete("/api/v1/sites/$archived").cookie(account.cookie))
            .andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup").cookie(account.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.siteCount").value(1))
            .andExpect(jsonPath("$.data.consent.totalEvents").value(1))
            .andExpect(jsonPath("$.data.consent.byAction.acceptAll").value(1))
            .andExpect(jsonPath("$.data.consent.byAction.rejectAll").value(0))
    }

    @Test
    fun `honours the half-open date range (from inclusive, to exclusive)`() {
        val account = subscribedUser(Plan.PRO)
        val siteId = createSite(account.cookie, uniqueDomain("rollup-range"))
        seedEvent(siteId, Instant.parse("2026-08-15T00:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // before
        seedEvent(siteId, Instant.parse("2026-08-16T12:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // inside
        seedEvent(siteId, Instant.parse("2026-08-17T00:00:00Z"), "accept_all", mapOf("statistics" to true), "en") // == to, excluded

        mockMvc
            .perform(
                get("/api/v1/analytics/accounts/rollup")
                    .param("from", "2026-08-16T00:00:00Z")
                    .param("to", "2026-08-17T00:00:00Z")
                    .cookie(account.cookie),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.consent.totalEvents").value(1))
    }

    @Test
    fun `empty roll-up for a pro account with no sites yet`() {
        val account = subscribedUser(Plan.PRO)

        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup").cookie(account.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.siteCount").value(0))
            .andExpect(jsonPath("$.data.consent.totalEvents").value(0))
            .andExpect(jsonPath("$.data.consent.trend.length()").value(0))
    }

    @Test
    fun `scopes the roll-up to the calling account only`() {
        val alice = subscribedUser(Plan.PRO)
        val aliceSite = createSite(alice.cookie, uniqueDomain("rollup-alice"))
        seedEvent(aliceSite, Instant.parse("2026-08-05T10:00:00Z"), "accept_all", mapOf("statistics" to true), "en")
        val bob = subscribedUser(Plan.PRO)

        // Bob owns no sites; Alice's events must never leak into his account roll-up.
        mockMvc
            .perform(get("/api/v1/analytics/accounts/rollup").cookie(bob.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.siteCount").value(0))
            .andExpect(jsonPath("$.data.consent.totalEvents").value(0))
    }
}
