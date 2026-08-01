package com.complyr.banner

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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * Full-stack authenticated banner-config management: the seeded v1 the GET returns, publishing a new
 * version through real Postgres, ownership scoping (foreign site → 404), semantic validation (400),
 * and that the endpoint requires auth.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class BannerConfigApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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

    private fun createSiteId(cookie: Cookie): String {
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
            .path("id")
            .asString()
    }

    private fun validBody(): String =
        """
        {
          "position": "top",
          "theme": { "primaryColor": "#111111", "background": "#ffffff", "textColor": "#000000" },
          "categories": [ { "key": "necessary" }, { "key": "statistics" } ],
          "languages": ["en"],
          "defaultLanguage": "en",
          "texts": {
            "en": {
              "title": "Cookies", "description": "We use cookies.",
              "acceptAll": "Accept", "rejectAll": "Reject", "save": "Save", "preferences": "Manage"
            }
          }
        }
        """.trimIndent()

    @Test
    fun `GET returns the auto-seeded v1 config for the owner`() {
        val cookie = registeredUserCookie()
        val siteId = createSiteId(cookie)

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", siteId).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.config.defaultLanguage").value("en"))
            .andExpect(jsonPath("$.data.config.categories[0].key").value("necessary"))
    }

    @Test
    fun `PUT publishes a new version the subsequent GET returns`() {
        val cookie = registeredUserCookie()
        val siteId = createSiteId(cookie)

        mockMvc
            .perform(
                put("/api/v1/sites/{siteId}/banner-config", siteId)
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.config.position").value("top"))
            .andExpect(jsonPath("$.data.config.languages.length()").value(1))

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", siteId).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.config.position").value("top"))
    }

    @Test
    fun `PUT with an invalid config is a 400 INVALID_BANNER_CONFIG`() {
        val cookie = registeredUserCookie()
        val siteId = createSiteId(cookie)
        val badColor = validBody().replace("#111111", "javascript:alert(1)")

        mockMvc
            .perform(
                put("/api/v1/sites/{siteId}/banner-config", siteId)
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(badColor),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_BANNER_CONFIG"))
    }

    @Test
    fun `a foreign site id is a 404, not another user's config`() {
        val owner = registeredUserCookie()
        val siteId = createSiteId(owner)
        val stranger = registeredUserCookie()

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", siteId).cookie(stranger))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `the endpoint requires authentication`() {
        val siteId = createSiteId(registeredUserCookie())

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", siteId))
            .andExpect(status().isUnauthorized)
    }
}
