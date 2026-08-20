package eu.cookiekeeper.site

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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * `GET /api/v1/sites/{id}/widget-status` end to end — including the part that matters most, that a real
 * widget beacon on the public endpoint is what flips the site's status to "active". Ownership scoping and
 * the auth requirement are covered here too; the state boundaries themselves are unit-tested in
 * [WidgetStatusServiceTest] against a fixed clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class WidgetStatusApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var objectMapper: ObjectMapper

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

    private data class Site(
        val id: String,
        val key: String,
    )

    private fun createSite(cookie: Cookie): Site {
        val created =
            mockMvc
                .perform(
                    post("/api/v1/sites")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"domain":"shop-${UUID.randomUUID().toString().take(8)}.example.com"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        val data = objectMapper.readTree(created.response.contentAsString).path("data")
        return Site(data.path("id").asString(), data.path("siteKey").asString())
    }

    @Test
    fun `a freshly created site has never been seen`() {
        val cookie = registeredUserCookie()
        val site = createSite(cookie)

        mockMvc
            .perform(get("/api/v1/sites/{id}/widget-status", site.id).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.state").value("never_seen"))
            .andExpect(jsonPath("$.data.lastSeenDay").doesNotExist())
            .andExpect(jsonPath("$.data.impressionsToday").value(0))
            .andExpect(jsonPath("$.data.impressionsInWindow").value(0))
            .andExpect(jsonPath("$.data.windowDays").value(WidgetStatusService.ACTIVE_WINDOW_DAYS))
    }

    @Test
    fun `a beacon from the widget flips the site to active on the same UTC day`() {
        val cookie = registeredUserCookie()
        val site = createSite(cookie)

        // The real public beacon, exactly as the installed widget fires it — this is the whole point of the
        // card: install confirmation comes from the widget itself, not from anything the dashboard asserts.
        mockMvc
            .perform(
                post("/api/v1/impression")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"siteKey":"${site.key}"}"""),
            ).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/sites/{id}/widget-status", site.id).cookie(cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state").value("active"))
            .andExpect(jsonPath("$.data.lastSeenDay").value(LocalDate.now(ZoneOffset.UTC).toString()))
            .andExpect(jsonPath("$.data.impressionsToday").value(1))
            .andExpect(jsonPath("$.data.impressionsInWindow").value(1))
    }

    @Test
    fun `a foreign site id is a 404, not another account's widget activity`() {
        val site = createSite(registeredUserCookie())
        val stranger = registeredUserCookie()

        mockMvc
            .perform(get("/api/v1/sites/{id}/widget-status", site.id).cookie(stranger))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `the endpoint requires authentication`() {
        val site = createSite(registeredUserCookie())

        mockMvc
            .perform(get("/api/v1/sites/{id}/widget-status", site.id))
            .andExpect(status().isUnauthorized)
    }
}
