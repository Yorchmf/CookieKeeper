package com.complyr.consent

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack banner-impression ingestion (Track 4 Slice D): the public, unauthenticated `POST
 * /api/v1/impression` endpoint that feeds the interaction-rate denominator. Proves the HTTP boundary the widget
 * beacon relies on — permitAll (no auth), site-key validation to an ACTIVE site (404 otherwise, no enumeration),
 * request validation on the site key, and that a real beacon folds into the `banner_impressions` counter through
 * real Postgres. Nothing personal is written: the endpoint stores only a per-site, per-day count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ImpressionApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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
        val header =
            requireNotNull(
                login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") },
            )
        return Cookie("cmplyr_at", header.substringAfter("=").substringBefore(";"))
    }

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
    fun `public impression post is accepted without auth and folds into the per-site-day counter`() {
        val siteKey = createSiteKey(registeredUserCookie())

        // Two beacons for the same site on the same server day collapse into one row with count 2.
        repeat(2) {
            mockMvc
                .perform(
                    post("/api/v1/impression")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"siteKey":"$siteKey"}"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recorded").value(true))
        }

        val total =
            jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(count), 0) FROM banner_impressions WHERE site_id = " +
                    "(SELECT id FROM sites WHERE site_key = ?)",
                Long::class.java,
                siteKey,
            )
        assertEquals(2L, total)
    }

    @Test
    fun `an unknown site key is a 404 and records no counter`() {
        mockMvc
            .perform(
                post("/api/v1/impression")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"siteKey":"pk_does_not_exist"}"""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `a blank site key is rejected as a 400 before any lookup`() {
        mockMvc
            .perform(
                post("/api/v1/impression")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"siteKey":"  "}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `an oversized site key is rejected as a 400`() {
        val oversized = "pk_" + "x".repeat(200)
        mockMvc
            .perform(
                post("/api/v1/impression")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"siteKey":"$oversized"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }
}
