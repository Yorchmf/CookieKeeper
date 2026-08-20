package eu.cookiekeeper.banner

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.billing.SubscriptionEntity
import eu.cookiekeeper.billing.SubscriptionRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * `POST /api/v1/sites/{siteId}/banner-config/copy` — applying one site's published banner to the account's
 * other sites. Covers the happy path (a new published version on every target, source untouched), the
 * all-or-nothing rollback when any target is not an owned ACTIVE site, self-only requests, and auth.
 *
 * Every account here is subscribed to BUSINESS: the feature only means anything above the single-site
 * Starter/Trial cap, so a multi-site fixture is the only one that exercises it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class BannerConfigCopyApiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var emailSender: RecordingEmailSender

    @Autowired private lateinit var objectMapper: ObjectMapper

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
        val header = assertNotNull(login.response.getHeaders("Set-Cookie").firstOrNull { it.startsWith("cmplyr_at=") })
        val userId = assertNotNull(userRepository.findByEmail(email)).id
        return Account(Cookie("cmplyr_at", header.substringAfter("=").substringBefore(";")), userId)
    }

    /** A BUSINESS-subscribed account, so the fixture can hold the several sites this feature is about. */
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

    /** Publishes a distinctive v2 on [siteId] so a copy of it is recognizable on the targets. */
    private fun publishDistinctiveConfig(
        cookie: Cookie,
        siteId: String,
    ) {
        mockMvc
            .perform(
                put("/api/v1/sites/{siteId}/banner-config", siteId)
                    .cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "position": "top",
                          "theme": { "primaryColor": "#ab00ef", "background": "#ffffff", "textColor": "#000000" },
                          "categories": [ { "key": "necessary" }, { "key": "statistics" } ],
                          "languages": ["de"],
                          "defaultLanguage": "de",
                          "texts": {
                            "de": {
                              "title": "Kekse", "description": "Wir nutzen Cookies.",
                              "acceptAll": "Annehmen", "rejectAll": "Ablehnen",
                              "save": "Speichern", "preferences": "Verwalten"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(2))
    }

    private fun copy(
        cookie: Cookie,
        siteId: String,
        targetIds: List<String>,
    ) = mockMvc.perform(
        post("/api/v1/sites/{siteId}/banner-config/copy", siteId)
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"targetSiteIds":[${targetIds.joinToString(",") { "\"$it\"" }}]}"""),
    )

    @Test
    fun `copies the source's published config onto every target as a new version`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)
        val targetA = createSiteId(account.cookie)
        val targetB = createSiteId(account.cookie)
        publishDistinctiveConfig(account.cookie, source)

        copy(account.cookie, source, listOf(targetA, targetB))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sourceVersion").value(2))
            .andExpect(jsonPath("$.data.copiedToSiteIds.length()").value(2))

        listOf(targetA, targetB).forEach { target ->
            mockMvc
                .perform(get("/api/v1/sites/{siteId}/banner-config", target).cookie(account.cookie))
                .andExpect(status().isOk)
                // The seeded v1 is untouched; the copy lands as a new v2 (configs are append-only).
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.config.position").value("top"))
                .andExpect(jsonPath("$.data.config.defaultLanguage").value("de"))
                .andExpect(jsonPath("$.data.config.theme.primaryColor").value("#ab00ef"))
        }

        // The source is read, never re-published — it must not gain a version from being copied.
        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", source).cookie(account.cookie))
            .andExpect(jsonPath("$.data.version").value(2))
    }

    @Test
    fun `the source is silently dropped when it appears in its own target list`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)
        val target = createSiteId(account.cookie)
        publishDistinctiveConfig(account.cookie, source)

        copy(account.cookie, source, listOf(source, target))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.copiedToSiteIds.length()").value(1))

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", source).cookie(account.cookie))
            .andExpect(jsonPath("$.data.version").value(2))
    }

    @Test
    fun `a request naming only the source is a 400 NO_BANNER_COPY_TARGETS`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)

        copy(account.cookie, source, listOf(source))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("NO_BANNER_COPY_TARGETS"))
    }

    @Test
    fun `an empty target list is rejected by bean validation`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)

        copy(account.cookie, source, emptyList()).andExpect(status().isBadRequest)
    }

    @Test
    fun `a foreign target is a 404 and no owned target is written`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)
        val ownedTarget = createSiteId(account.cookie)
        publishDistinctiveConfig(account.cookie, source)
        val strangersSite = createSiteId(businessUser().cookie)

        copy(account.cookie, source, listOf(ownedTarget, strangersSite))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))

        // All-or-nothing: the owned target must still be on its seeded v1.
        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", ownedTarget).cookie(account.cookie))
            .andExpect(jsonPath("$.data.version").value(1))
    }

    @Test
    fun `an archived target is a 404 and no owned target is written`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)
        val ownedTarget = createSiteId(account.cookie)
        val archived = createSiteId(account.cookie)
        publishDistinctiveConfig(account.cookie, source)
        mockMvc.perform(delete("/api/v1/sites/{id}", archived).cookie(account.cookie)).andExpect(status().isOk)

        copy(account.cookie, source, listOf(ownedTarget, archived))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))

        mockMvc
            .perform(get("/api/v1/sites/{siteId}/banner-config", ownedTarget).cookie(account.cookie))
            .andExpect(jsonPath("$.data.version").value(1))
    }

    @Test
    fun `a foreign source site id is a 404`() {
        val owner = businessUser()
        val source = createSiteId(owner.cookie)
        val stranger = businessUser()
        val strangersSite = createSiteId(stranger.cookie)

        copy(stranger.cookie, source, listOf(strangersSite))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `the endpoint requires authentication`() {
        val account = businessUser()
        val source = createSiteId(account.cookie)
        val target = createSiteId(account.cookie)

        mockMvc
            .perform(
                post("/api/v1/sites/{siteId}/banner-config/copy", source)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"targetSiteIds":["$target"]}"""),
            ).andExpect(status().isUnauthorized)
    }
}
