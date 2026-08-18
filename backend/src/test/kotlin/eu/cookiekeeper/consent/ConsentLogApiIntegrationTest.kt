package eu.cookiekeeper.consent

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * `GET /api/v1/sites/{siteId}/consent-events` — the dashboard audit log. Covers the authenticated,
 * ownership-scoped envelope, keyset pagination (newest-first + cursor to the older page), the optional
 * filters (action / lang / visitor / date range), and that per-visitor PII (`ipHash`, `ua`) never leaves
 * the server. Events are seeded straight through the repository since the widget POST path is exercised
 * elsewhere; here the read side is what's under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ConsentLogApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var consentEventRepository: ConsentEventRepository

    private fun registeredUser(): Cookie {
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
        return Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";"))
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
        lang: String? = "en",
        visitorId: UUID = UUID.randomUUID(),
    ): UUID =
        requireNotNull(
            consentEventRepository
                .save(
                    ConsentEventEntity(
                        siteId = siteId,
                        visitorId = visitorId,
                        action = action,
                        categories = mapOf("statistics" to true, "marketing" to false),
                        lang = lang,
                        ipHash = "hash-${UUID.randomUUID()}",
                        ua = "Mozilla/5.0 test-agent",
                        createdAt = createdAt,
                    ),
                ).eventId,
        )

    private fun uniqueDomain(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8)}.example.com"

    @Test
    fun `returns a site's events newest-first without leaking ip hash or user agent`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("log"))
        val base = Instant.parse("2026-08-01T10:00:00Z")
        seedEvent(siteId, base, action = "reject_all")
        val newer = seedEvent(siteId, base.plus(1, ChronoUnit.HOURS), action = "accept_all")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].eventId").value(newer.toString()))
            .andExpect(jsonPath("$.data[0].action").value("accept_all"))
            // Payload the dashboard actually renders is present and correctly mapped...
            .andExpect(jsonPath("$.data[0].categories.statistics").value(true))
            .andExpect(jsonPath("$.data[0].categories.marketing").value(false))
            .andExpect(jsonPath("$.data[0].lang").value("en"))
            // ...but per-visitor PII never crosses the boundary.
            .andExpect(jsonPath("$.data[0].ipHash").doesNotExist())
            .andExpect(jsonPath("$.data[0].ua").doesNotExist())
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist())
    }

    @Test
    fun `orders by created_at, not insertion order, when a backdated event is written last`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("order"))
        // Seed newest-first by createdAt but out of insertion order: the last row written is the OLDEST event.
        // If ordering keyed off eventId (UUIDv7 mint time) it would sort last-written first and this would fail.
        val newest = seedEvent(siteId, Instant.parse("2026-08-03T00:00:00Z"), action = "accept_all")
        seedEvent(siteId, Instant.parse("2026-08-02T00:00:00Z"), action = "reject_all")
        seedEvent(siteId, Instant.parse("2026-08-01T00:00:00Z"), action = "custom")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].eventId").value(newest.toString()))
            .andExpect(jsonPath("$.data[0].action").value("accept_all"))
            .andExpect(jsonPath("$.data[2].action").value("custom"))
    }

    @Test
    fun `clamps an over-large limit to the page maximum`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("clamp"))
        val base = Instant.parse("2026-08-04T00:00:00Z")
        repeat(3) { seedEvent(siteId, base.plus(it.toLong(), ChronoUnit.MINUTES)) }

        // limit far above MAX_LIMIT must not error and must return everything (3 < clamped 200), no next page.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("limit", "100000").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist())
    }

    @Test
    fun `returns an empty page with no cursor for a site that has no events`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("empty"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist())
    }

    @Test
    fun `paginates via the cursor when more rows remain`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("page"))
        val base = Instant.parse("2026-08-05T08:00:00Z")
        repeat(3) { seedEvent(siteId, base.plus(it.toLong(), ChronoUnit.MINUTES)) }

        val firstPage =
            mockMvc
                .perform(get("/api/v1/sites/$siteId/consent-events").param("limit", "2").cookie(alice))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.nextCursor").isNotEmpty)
                .andReturn()
        val cursor =
            objectMapper
                .readTree(firstPage.response.contentAsString)
                .path("meta")
                .path("nextCursor")
                .asString()

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("limit", "2").param("cursor", cursor).cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.meta.nextCursor").doesNotExist())
    }

    @Test
    fun `filters by action, lang and visitor`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("filter"))
        val base = Instant.parse("2026-08-10T12:00:00Z")
        val visitor = UUID.randomUUID()
        seedEvent(siteId, base, action = "accept_all", lang = "en", visitorId = visitor)
        seedEvent(siteId, base.plus(1, ChronoUnit.MINUTES), action = "reject_all", lang = "de")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("action", "reject_all").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].action").value("reject_all"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("lang", "de").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].lang").value("de"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("visitorId", visitor.toString()).cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].visitorId").value(visitor.toString()))
    }

    @Test
    fun `filters by half-open date range (from inclusive, to exclusive)`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("range"))
        seedEvent(siteId, Instant.parse("2026-08-15T00:00:00Z")) // before window
        seedEvent(siteId, Instant.parse("2026-08-16T12:00:00Z")) // inside window
        seedEvent(siteId, Instant.parse("2026-08-17T00:00:00Z")) // == to, excluded

        mockMvc
            .perform(
                get("/api/v1/sites/$siteId/consent-events")
                    .param("from", "2026-08-16T00:00:00Z")
                    .param("to", "2026-08-17T00:00:00Z")
                    .cookie(alice),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
    }

    @Test
    fun `rejects a malformed cursor with 400`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("badcursor"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").param("cursor", "garbage!!").cookie(alice))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
    }

    @Test
    fun `another user cannot read events for a site they do not own`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("owned"))
        seedEvent(siteId, Instant.parse("2026-08-20T00:00:00Z"))
        val bob = registeredUser()

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `anonymous request is rejected with 401`() {
        val alice = registeredUser()
        val siteId = createSite(alice, uniqueDomain("anon"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/consent-events"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
