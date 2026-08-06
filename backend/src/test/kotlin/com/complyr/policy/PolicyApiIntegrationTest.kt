package com.complyr.policy

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanEntity
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
import com.complyr.scan.ScanTrigger
import com.complyr.site.SiteRepository
import com.complyr.site.VerificationMethod
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
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * Full policy slice through HTTP + Postgres: generate publishes a versioned, per-language policy; the
 * authenticated `current` view reports it; the public hosted read serves the escaped HTML by opaque id
 * with forgiving language selection; and the security edges hold (ownership isolation, unauthenticated
 * generate, and the unknown-id 404 parity that keeps the public id from being an existence oracle).
 *
 * The hosted read is additionally gated on domain verification (ADR-17) while the owner's preview is
 * not — both halves are asserted here, since a gate with no way to see what you're publishing would
 * make the feature unusable, and a preview that leaked the page publicly would make the gate pointless.
 * A completed scan is seeded directly since the crawler is `scanner`-profile only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class PolicyApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var scanRepository: ScanRepository

    @Autowired
    private lateinit var scanCookieRepository: ScanCookieRepository

    @Autowired
    private lateinit var siteRepository: SiteRepository

    /**
     * Flip a site to verified straight in the DB. The real endpoint (`POST /sites/{id}/verify`) reaches
     * out to the customer's domain, which has no place in a policy test — what matters here is only that
     * the hosted read consults `verified_at`, which this sets exactly as the verification service does.
     */
    private fun markVerified(siteId: UUID) {
        val site = siteRepository.findById(siteId).orElseThrow()
        siteRepository.save(
            site.copy(verifiedAt = Instant.now(), verificationMethod = VerificationMethod.SNIPPET, updatedAt = Instant.now()),
        )
    }

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

    private fun seedCompletedScan(siteId: UUID) {
        val now = Instant.now()
        val scan =
            scanRepository.save(
                ScanEntity(
                    siteId = siteId,
                    status = ScanStatus.DONE,
                    trigger = ScanTrigger.MANUAL,
                    startedAt = now,
                    finishedAt = now,
                    pagesCrawled = 2,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        scanCookieRepository.saveAll(
            listOf(
                ScanCookieEntity(scanId = scan.id, name = "_ga", category = "statistics", provider = "Google Analytics", isKnown = true),
                // An unclassified cookie whose name carries HTML metacharacters — must be escaped, never
                // reflected raw into the hosted document.
                ScanCookieEntity(scanId = scan.id, name = "<script>evil()</script>", isKnown = false),
            ),
        )
    }

    private fun generate(
        cookie: Cookie,
        siteId: UUID,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/sites/$siteId/policy")
            .cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    @Test
    fun `generate publishes a versioned policy, current reports it, and the hosted page serves escaped HTML`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "policy-${UUID.randomUUID().toString().take(8)}.example.com")
        seedCompletedScan(siteId)

        val generated =
            generate(
                alice,
                siteId,
                """{"companyName":"Acme GmbH","contactEmail":"privacy@acme.example.com","languages":["en","de"]}""",
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.languages.length()").value(2))
                .andReturn()
        val publicId =
            objectMapper
                .readTree(generated.response.contentAsString)
                .path("data")
                .path("publicId")
                .asString()

        // Authenticated current view reflects the published version and its languages (sorted).
        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.publicId").value(publicId))
            .andExpect(jsonPath("$.data.languages[0]").value("de"))
            .andExpect(jsonPath("$.data.languages[1]").value("en"))

        // Public hosted read, no auth, by opaque id: serves the requested language and escapes the
        // attacker-influenced cookie name (no raw <script> in the HTML). Publishing needs a verified
        // domain (ADR-17), so flip the site first — the gate itself is asserted below.
        markVerified(siteId)
        val hosted =
            mockMvc
                .perform(get("/api/v1/public/policy/$publicId").param("lang", "de"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.language").value("de"))
                .andExpect(jsonPath("$.data.companyName").value("Acme GmbH"))
                .andExpect(jsonPath("$.data.availableLanguages.length()").value(2))
                .andReturn()
        val html =
            objectMapper
                .readTree(hosted.response.contentAsString)
                .path("data")
                .path("html")
                .asString()
        assert(!html.contains("<script>evil()")) { "cookie name must be HTML-escaped in the hosted policy" }
        assert(html.contains("&lt;script&gt;")) { "the escaped cookie name should still be disclosed" }
    }

    @Test
    fun `regenerating bumps the version but keeps the same public id`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "republish-${UUID.randomUUID().toString().take(8)}.example.com")

        val first =
            generate(alice, siteId, """{"companyName":"Acme GmbH","contactEmail":"p@acme.example.com","languages":["en"]}""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn()
        val firstPublicId =
            objectMapper
                .readTree(first.response.contentAsString)
                .path("data")
                .path("publicId")
                .asString()

        generate(alice, siteId, """{"companyName":"Acme GmbH Renamed","contactEmail":"p@acme.example.com","languages":["en"]}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(2))
            .andExpect(jsonPath("$.data.publicId").value(firstPublicId))
    }

    @Test
    fun `an all-unsupported language list is rejected as a 400`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "badlang-${UUID.randomUUID().toString().take(8)}.example.com")

        generate(alice, siteId, """{"companyName":"Acme","contactEmail":"p@acme.example.com","languages":["xx","zz"]}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_POLICY_LANGUAGE"))
    }

    @Test
    fun `user B cannot generate or read user A's policy`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val siteId = createSite(alice, "iso-${UUID.randomUUID().toString().take(8)}.example.com")

        generate(bob, siteId, """{"companyName":"Acme","contactEmail":"p@acme.example.com","languages":["en"]}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
    }

    @Test
    fun `current is a 404 before any policy is generated`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "none-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy").cookie(alice))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"))
    }

    @Test
    fun `an unknown public id returns the generic 404, not an existence oracle`() {
        mockMvc
            .perform(get("/api/v1/public/policy/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"))
    }

    @Test
    fun `the hosted page is a 404 until the domain is verified, then serves the policy`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "gate-${UUID.randomUUID().toString().take(8)}.example.com")

        val generated =
            generate(alice, siteId, """{"companyName":"Acme GmbH","contactEmail":"p@acme.example.com","languages":["en"]}""")
                .andExpect(status().isOk)
                .andReturn()
        val publicId =
            objectMapper
                .readTree(generated.response.contentAsString)
                .path("data")
                .path("publicId")
                .asString()

        // Published but unverified: the public page must be indistinguishable from an unknown id, so an
        // unverified customer can never publish a Complyr-hosted page for a domain they don't control.
        mockMvc
            .perform(get("/api/v1/public/policy/$publicId"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"))

        markVerified(siteId)

        mockMvc
            .perform(get("/api/v1/public/policy/$publicId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.companyName").value("Acme GmbH"))
    }

    @Test
    fun `the owner previews an unverified site's policy, which the public page still refuses`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val siteId = createSite(alice, "preview-${UUID.randomUUID().toString().take(8)}.example.com")

        generate(alice, siteId, """{"companyName":"Acme GmbH","contactEmail":"p@acme.example.com","languages":["en","de"]}""")
            .andExpect(status().isOk)

        // The activation loop depends on this: see what you're about to publish, then go verify.
        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy/preview").cookie(alice).param("lang", "de"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.language").value("de"))
            .andExpect(jsonPath("$.data.companyName").value("Acme GmbH"))
            .andExpect(jsonPath("$.data.availableLanguages.length()").value(2))
            .andExpect(jsonPath("$.data.html").isNotEmpty)

        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy/preview").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy/preview"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `preview is a 404 before any policy is generated`() {
        val alice = registeredUser()
        val siteId = createSite(alice, "nopreview-${UUID.randomUUID().toString().take(8)}.example.com")

        mockMvc
            .perform(get("/api/v1/sites/$siteId/policy/preview").cookie(alice))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"))
    }

    @Test
    fun `generate requires authentication`() {
        mockMvc
            .perform(
                post("/api/v1/sites/${UUID.randomUUID()}/policy")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"companyName":"Acme","contactEmail":"p@acme.example.com"}"""),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
