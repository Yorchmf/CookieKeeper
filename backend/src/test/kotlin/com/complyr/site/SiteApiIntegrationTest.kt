package com.complyr.site

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two-user site API test: ownership isolation (user B sees 404 on user A's site),
 * verified-email gate, soft archive + domain re-registration, embed snippet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class SiteApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** Signs up (optionally verifies) and logs in; returns the access-token cookie. */
    private fun registeredUser(verified: Boolean = true): Cookie {
        val email = "user-${UUID.randomUUID()}@example.com"
        mockMvc
            .perform(
                post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"s3cret-password","locale":"en"}"""),
            ).andExpect(status().isCreated)
        if (verified) {
            mockMvc
                .perform(
                    post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"token":"${emailSender.lastTokenFor(email)}"}"""),
                ).andExpect(status().isOk)
        }
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
    ): String {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/sites")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"domain":"$domain"}"""),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
        val tree = objectMapper.readTree(result.response.contentAsString)
        return tree.path("data").path("id").asString()
    }

    @Test
    fun `unverified users get 403 EMAIL_NOT_VERIFIED when creating a site`() {
        val cookie = registeredUser(verified = false)

        mockMvc
            .perform(
                post("/api/v1/sites")
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"example.com"}"""),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"))
    }

    @Test
    fun `site lifecycle - create, list with meta, detail snippet, duplicate, archive, re-register`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "HTTPS://Shop.Example.COM/checkout")

        // Duplicate (already normalized to shop.example.com) → 409.
        mockMvc
            .perform(
                post("/api/v1/sites")
                    .cookie(alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"shop.example.com"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("DOMAIN_ALREADY_REGISTERED"))

        // Invalid domain → 400.
        mockMvc
            .perform(
                post("/api/v1/sites")
                    .cookie(alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"192.168.0.1"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_DOMAIN"))

        // List: envelope meta carries the total.
        mockMvc
            .perform(get("/api/v1/sites").param("status", "active").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].domain").value("shop.example.com"))
            .andExpect(jsonPath("$.meta.total").value(1))

        // Detail: embed snippet contains the site key and the CDN base URL.
        val detail =
            mockMvc
                .perform(get("/api/v1/sites/$siteId").cookie(alice))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.embedSnippet").isNotEmpty)
                .andReturn()
        val data = objectMapper.readTree(detail.response.contentAsString).path("data")
        val snippet = data.path("embedSnippet").asString()
        val siteKey = data.path("siteKey").asString()
        assertTrue(siteKey.startsWith("pk_"))
        assertTrue(snippet.contains("data-complyr=\"$siteKey\""), "snippet must carry the site key: $snippet")
        assertTrue(snippet.contains("/v1.js"), "snippet must load the versioned widget: $snippet")

        // PATCH: rename the domain.
        mockMvc
            .perform(
                patch("/api/v1/sites/$siteId")
                    .cookie(alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"store.example.com"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.domain").value("store.example.com"))

        // DELETE: soft archive.
        mockMvc
            .perform(delete("/api/v1/sites/$siteId").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.archived").value(true))
        mockMvc
            .perform(get("/api/v1/sites").cookie(alice))
            .andExpect(jsonPath("$.meta.total").value(0))
        mockMvc
            .perform(get("/api/v1/sites").param("status", "archived").cookie(alice))
            .andExpect(jsonPath("$.meta.total").value(1))

        // The archived domain can be registered again.
        createSite(alice, "store.example.com")
    }

    @Test
    fun `list with an unknown status value returns 400 INVALID_QUERY_PARAM`() {
        val alice = registeredUser()

        mockMvc
            .perform(get("/api/v1/sites").param("status", "bogus").cookie(alice))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAM"))
    }

    @Test
    fun `user B cannot read, update or archive user A's site`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val siteId = createSite(alice, "alice-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
        mockMvc
            .perform(
                patch("/api/v1/sites/$siteId")
                    .cookie(bob)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"hijack.example.com"}"""),
            ).andExpect(status().isNotFound)
        mockMvc
            .perform(delete("/api/v1/sites/$siteId").cookie(bob))
            .andExpect(status().isNotFound)

        // Bob's list does not contain Alice's site.
        mockMvc
            .perform(get("/api/v1/sites").cookie(bob))
            .andExpect(jsonPath("$.meta.total").value(0))

        // And without any token the collection is protected.
        mockMvc
            .perform(get("/api/v1/sites"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
