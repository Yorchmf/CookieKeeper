package com.complyr.scan

import com.complyr.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The public, token-scoped read API: the free teaser (`GET /api/v1/public-scan/{token}`) and the
 * email-gated report (`POST .../report`). Covers that both are reachable without a JWT, that the
 * teaser withholds cookie-level detail while the report reveals it and captures the lead email, and
 * that an unknown token and an expired token return one identical generic 404 (no honeypot oracle).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PublicScanReadApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var publicScanRepository: PublicScanRepository

    @Autowired
    private lateinit var publicScanCookieRepository: PublicScanCookieRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE jobs, public_scans CASCADE")
    }

    private fun persistDoneScan(
        token: String,
        expiresAt: Instant = Instant.now().plus(Duration.ofDays(7)),
    ): PublicScanEntity {
        val scan =
            publicScanRepository.save(
                PublicScanEntity(
                    domain = "acme.example.com",
                    status = ScanStatus.DONE,
                    publicToken = token,
                    createdAt = Instant.now().minus(Duration.ofMinutes(5)),
                    updatedAt = Instant.now().minus(Duration.ofMinutes(5)),
                    expiresAt = expiresAt,
                ),
            )
        publicScanCookieRepository.save(
            PublicScanCookieEntity(
                publicScanId = scan.id,
                name = "_ga",
                category = "statistics",
                provider = "Google Analytics",
                isKnown = true,
            ),
        )
        return scan
    }

    @Test
    fun `the teaser is readable without a JWT and reports counts but withholds cookie names and providers`() {
        val scan = persistDoneScan("tok_${UUID.randomUUID()}")

        mockMvc
            .perform(get("/api/v1/public-scan/${scan.publicToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.verdict.totalCookies").value(1))
            .andExpect(jsonPath("$.data.verdict.cookiesByCategory.statistics").value(1))
            // The teaser is counts-only: the cookie name and provider stay behind the email gate.
            .andExpect(jsonPath("$.data.verdict.cookiesByCategory.statistics[0]").doesNotExist())
            .andExpect(jsonPath("$.data.cookiesByCategory").doesNotExist())
    }

    @Test
    fun `an unknown token returns a generic 404 not-found envelope`() {
        mockMvc
            .perform(get("/api/v1/public-scan/does-not-exist"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PUBLIC_SCAN_NOT_FOUND"))
    }

    @Test
    fun `an expired token returns the identical 404 as an unknown one (honeypot indistinguishable from expiry)`() {
        val expired = persistDoneScan("tok_${UUID.randomUUID()}", expiresAt = Instant.now().minus(Duration.ofSeconds(1)))

        mockMvc
            .perform(get("/api/v1/public-scan/${expired.publicToken}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("PUBLIC_SCAN_NOT_FOUND"))
    }

    @Test
    fun `unlocking the report reveals cookie detail and persists the lead email`() {
        val scan = persistDoneScan("tok_${UUID.randomUUID()}")

        mockMvc
            .perform(
                post("/api/v1/public-scan/${scan.publicToken}/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"lead@example.com"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("done"))
            .andExpect(jsonPath("$.data.cookiesByCategory.statistics[0].name").value("_ga"))
            .andExpect(jsonPath("$.data.cookiesByCategory.statistics[0].provider").value("Google Analytics"))

        // The email is now captured on the row (the lead), where before it was null.
        assertEquals("lead@example.com", publicScanRepository.findById(scan.id).orElseThrow().email)
    }

    @Test
    fun `unlocking the report with a malformed email fails bean validation`() {
        val scan = persistDoneScan("tok_${UUID.randomUUID()}")

        mockMvc
            .perform(
                post("/api/v1/public-scan/${scan.publicToken}/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"not-an-email"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `unlocking the report for an unknown token returns the generic 404`() {
        mockMvc
            .perform(
                post("/api/v1/public-scan/does-not-exist/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"lead@example.com"}"""),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("PUBLIC_SCAN_NOT_FOUND"))
    }
}
