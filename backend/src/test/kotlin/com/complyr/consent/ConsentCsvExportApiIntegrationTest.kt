package com.complyr.consent

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.auth.UserRepository
import com.complyr.billing.Plan
import com.complyr.billing.SubscriptionEntity
import com.complyr.billing.SubscriptionRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/sites/{siteId}/consent-events/export.csv` — the Business-plan CSV export. Covers the entitlement
 * gate (403 for non-Business), ownership (404), auth (401), and the happy path: a `text/csv` attachment streamed
 * as header + rows with per-visitor PII (`ipHash`, `ua`) still excluded. The denial paths must fail *before* the
 * stream starts, so they assert a normal JSON error envelope rather than a truncated 200.
 */
@SpringBootTest(properties = ["complyr.consent.export-batch-size=2"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ConsentCsvExportApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var consentEventRepository: ConsentEventRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var subscriptionRepository: SubscriptionRepository

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

    // A Business subscription is what unlocks csvExport; granting it directly mirrors what the Stripe webhook
    // would persist, without driving the whole billing flow (exercised in the billing suite).
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
        action: String = "accept_all",
        visitorId: UUID = UUID.randomUUID(),
    ) {
        consentEventRepository.save(
            ConsentEventEntity(
                siteId = siteId,
                visitorId = visitorId,
                action = action,
                categories = mapOf("statistics" to true, "marketing" to false),
                lang = "en",
                ipHash = "hash-secret-${UUID.randomUUID()}",
                ua = "Mozilla/5.0 secret-agent",
                createdAt = createdAt,
            ),
        )
    }

    private fun uniqueDomain(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}.example.com"

    @Test
    fun `business account streams a csv attachment of the log without leaking pii`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("export"))
        val base = Instant.parse("2026-08-10T09:00:00Z")
        val newerVisitor = UUID.randomUUID()
        seedEvent(siteId, base, action = "reject_all")
        seedEvent(siteId, base.plus(1, ChronoUnit.HOURS), action = "accept_all", visitorId = newerVisitor)

        val started =
            mockMvc
                .perform(get("/api/v1/sites/$siteId/consent-events/export.csv").cookie(account.cookie))
                .andExpect(request().asyncStarted())
                .andReturn()
        val response =
            mockMvc
                .perform(asyncDispatch(started))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"consent-events.csv\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                .andReturn()
                .response

        val csv = response.contentAsString
        assertTrue(csv.startsWith("created_at,event_id,visitor_id,action,lang,banner_version,policy_version,categories\r\n"))
        assertTrue(csv.contains(newerVisitor.toString()), "expected the newer event's visitor id in the export")
        assertTrue(csv.contains(",accept_all,") && csv.contains(",reject_all,"))
        // PII the read DTO hides must not reappear in the export.
        assertFalse(csv.contains("Mozilla"), "user agent leaked into CSV")
        assertFalse(csv.contains("hash-secret"), "ip hash leaked into CSV")
    }

    @Test
    fun `export walks the full history across multiple keyset batches without gaps or duplicates`() {
        // The class runs with export-batch-size=2, so 5 events force three keyset pages (2 + 2 + 1),
        // exercising real cursor advancement rather than a single fetch that happens to cover everything.
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("multi"))
        val base = Instant.parse("2026-08-13T00:00:00Z")
        val visitors = (0 until 5).map { UUID.randomUUID() }
        visitors.forEachIndexed { index, visitor ->
            seedEvent(siteId, base.plus(index.toLong(), ChronoUnit.MINUTES), visitorId = visitor)
        }

        val started =
            mockMvc
                .perform(get("/api/v1/sites/$siteId/consent-events/export.csv").cookie(account.cookie))
                .andExpect(request().asyncStarted())
                .andReturn()
        val csv =
            mockMvc
                .perform(asyncDispatch(started))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val dataRows = csv.trim().split("\r\n").drop(1) // drop the header line
        assertEquals(5, dataRows.size, "every seeded event must appear exactly once across all batches")
        visitors.forEach { visitor ->
            assertEquals(1, dataRows.count { it.contains(visitor.toString()) }, "row for $visitor duplicated or dropped")
        }
        // Newest-first ordering must hold across the page boundaries, not just within a batch.
        assertTrue(dataRows.first().contains(visitors[4].toString()), "newest event should lead the export")
        assertTrue(dataRows.last().contains(visitors[0].toString()), "oldest event should close the export")
    }

    @Test
    fun `non-business account is denied export with 403 before any stream starts`() {
        val account = registeredUser() // trial: csvExport = false
        val siteId = createSite(account.cookie, uniqueDomain("trial"))
        seedEvent(siteId, Instant.parse("2026-08-11T00:00:00Z"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events/export.csv").cookie(account.cookie))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("CSV_EXPORT_NOT_ENTITLED"))
    }

    @Test
    fun `entitled user cannot export a site they do not own`() {
        val owner = registeredUser()
        val siteId = createSite(owner.cookie, uniqueDomain("owned"))
        seedEvent(siteId, Instant.parse("2026-08-12T00:00:00Z"))
        val intruder = businessUser() // passes entitlement, so this proves ownership (not plan) blocks them

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events/export.csv").cookie(intruder.cookie))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `anonymous export request is rejected with 401`() {
        val account = businessUser()
        val siteId = createSite(account.cookie, uniqueDomain("anon"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events/export.csv"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
