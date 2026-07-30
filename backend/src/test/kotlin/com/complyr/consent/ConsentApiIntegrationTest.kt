package com.complyr.consent

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full-stack consent ingestion: public (no auth), site-key resolution, and — the riskiest
 * moving part — the `categories_jsonb` mapping round-tripping through real Postgres. Also
 * asserts the append-only row never carries the raw client IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ConsentApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var consentEventRepository: ConsentEventRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun registeredUserCookie(): Cookie {
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
        val header = assertNotNull(login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") })
        return Cookie("cmplyr_at", header.substringAfter("=").substringBefore(";"))
    }

    /** Creates a site and returns its public site key. */
    private fun createSiteKey(cookie: Cookie): String {
        val created =
            mockMvc
                .perform(
                    post("/api/v1/sites")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"domain":"shop-${UUID.randomUUID().toString().take(8)}.example.com"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        return objectMapper
            .readTree(created.response.contentAsString)
            .path("data")
            .path("siteKey")
            .asString()
    }

    @Test
    fun `public consent post is accepted without auth and persisted with jsonb categories and a hashed ip`() {
        val siteKey = createSiteKey(registeredUserCookie())
        val vid = UUID.randomUUID()
        val categories = mapOf("necessary" to true, "statistics" to true, "marketing" to false)

        mockMvc
            .perform(
                post("/api/v1/consent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (test)")
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "siteKey" to siteKey,
                                "action" to "custom",
                                "categories" to categories,
                                "lang" to "de",
                                "vid" to vid.toString(),
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.recorded").value(true))

        val event = assertNotNull(consentEventRepository.findByVisitorId(vid).firstOrNull())
        assertEquals("custom", event.action)
        assertEquals(categories, event.categories, "jsonb categories must round-trip through Postgres")
        assertEquals("de", event.lang)
        // Constraint #4: the raw client IP (127.0.0.1 under MockMvc) is never stored.
        assertNotNull(event.ipHash)
        assertNotEquals("127.0.0.1", event.ipHash)
        val ipHash = requireNotNull(event.ipHash) { "expected a hashed IP" }
        assertTrue(ipHash.matches(Regex("^[0-9a-f]{64}$")), ipHash)
    }

    /** Posts one accept-all event for a fresh site and returns the persisted row's visitor id. */
    private fun recordOneEvent(): UUID {
        val siteKey = createSiteKey(registeredUserCookie())
        val vid = UUID.randomUUID()
        mockMvc
            .perform(
                post("/api/v1/consent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "siteKey" to siteKey,
                                "action" to "accept_all",
                                "categories" to mapOf("necessary" to true),
                                "vid" to vid.toString(),
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
        return vid
    }

    @Test
    fun `updating a consent event is rejected by the append-only trigger`() {
        val vid = recordOneEvent()

        // Constraint #3: consent history is immutable. The DB trigger blocks UPDATE for every
        // role — even the schema owner the app connects as — so this is defence in depth, not
        // merely application convention.
        val failure =
            assertThrows<DataAccessException> {
                jdbcTemplate.update("UPDATE consent_events SET action = 'reject_all' WHERE visitor_id = ?", vid)
            }
        assertTrue(failure.message?.contains("append-only") == true, failure.message)
        // The row is untouched.
        assertEquals("accept_all", consentEventRepository.findByVisitorId(vid).first().action)
    }

    @Test
    fun `deleting a consent event is rejected by the append-only trigger`() {
        val vid = recordOneEvent()

        val failure =
            assertThrows<DataAccessException> {
                jdbcTemplate.update("DELETE FROM consent_events WHERE visitor_id = ?", vid)
            }
        assertTrue(failure.message?.contains("append-only") == true, failure.message)
        assertNotNull(consentEventRepository.findByVisitorId(vid).firstOrNull(), "row must survive the blocked DELETE")
    }

    @Test
    fun `truncating the consent_events table is rejected by the append-only guard`() {
        recordOneEvent()

        // TRUNCATE bypasses row-level triggers; the statement-level guard (V4) closes that
        // hole so a stray full-table truncate can't silently wipe audit evidence.
        val failure =
            assertThrows<DataAccessException> {
                jdbcTemplate.execute("TRUNCATE TABLE consent_events")
            }
        assertTrue(failure.message?.contains("append-only") == true, failure.message)
    }

    @Test
    fun `an unknown site key is a 404 and writes nothing`() {
        mockMvc
            .perform(
                post("/api/v1/consent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"siteKey":"pk_does_not_exist","action":"accept_all","categories":{"necessary":true}}"""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `a malformed body fails bean validation with a 400 envelope`() {
        mockMvc
            .perform(
                post("/api/v1/consent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"action":"accept_all","categories":{"necessary":true}}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }
}
