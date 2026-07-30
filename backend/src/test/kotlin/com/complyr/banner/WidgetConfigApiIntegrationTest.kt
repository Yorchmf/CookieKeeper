package com.complyr.banner

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * Full-stack widget-config read: public (no auth), site-key resolution, the `config_jsonb`
 * document round-tripping through real Postgres, and the cache header the CDN relies on.
 * Also asserts the default config is auto-seeded the moment a site is created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class WidgetConfigApiIntegrationTest {
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
    fun `creating a site auto-seeds a cacheable v1 config the public read serves without auth`() {
        val siteKey = createSiteKey(registeredUserCookie())

        mockMvc
            .perform(get("/api/v1/widget-config/{siteKey}", siteKey))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=300")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.siteKey").value(siteKey))
            .andExpect(jsonPath("$.data.bannerVersion").value(1))
            // The config_jsonb document round-trips through Postgres with the default taxonomy and languages.
            .andExpect(jsonPath("$.data.config.defaultLanguage").value("en"))
            .andExpect(jsonPath("$.data.config.categories[0].key").value("necessary"))
            .andExpect(jsonPath("$.data.config.languages", hasItems("en", "de", "fr", "es", "it")))
    }

    @Test
    fun `an unknown site key is a 404 with the SITE_NOT_FOUND envelope`() {
        mockMvc
            .perform(get("/api/v1/widget-config/{siteKey}", "pk_does_not_exist"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }
}
