package eu.cookiekeeper.scan

import eu.cookiekeeper.TestcontainersConfiguration
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The public, unauthenticated free-scan endpoint (`POST /api/v1/public-scan`). Covers that it is
 * reachable without a JWT (permitAll), enqueues a queued scan and returns its opaque token, rejects a
 * non-public domain with the standard error envelope, and serves a fresh completed scan from the 24h
 * per-domain cache instead of enqueuing a second crawl.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PublicScanApiIntegrationTest {
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

    @Test
    fun `anonymous caller can request a scan and gets a queued token without authentication`() {
        mockMvc
            .perform(
                post("/api/v1/public-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"HTTPS://Acme.Example.com/pricing"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.token").isNotEmpty)

        // The messy input was normalized to the bare host before persistence.
        val scan = publicScanRepository.findAll().single()
        assertEquals("acme.example.com", scan.domain)
        assertEquals(ScanStatus.QUEUED, scan.status)
        // The requester IP is captured only as its rotating-salt hash — never the raw address.
        val ipHash = assertNotNull(scan.ipHash, "the source IP must be persisted as a hash for abuse analysis")
        assertTrue(ipHash.none { it == '.' || it == ':' }, "ip_hash must not look like a raw IP")
    }

    @Test
    fun `a request with the honeypot field populated is silently accepted but never persisted`() {
        mockMvc
            .perform(
                post("/api/v1/public-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"acme.example.com","website":"http://spam.example"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            // A plausible queued response so the bot gets no signal it was detected...
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.token").isNotEmpty)

        // ...but nothing was enqueued: no scan row and no job were created.
        assertTrue(publicScanRepository.findAll().isEmpty(), "a honeypot hit must not persist a scan")
    }

    @Test
    fun `a non-public domain is rejected with a 400 INVALID_DOMAIN envelope`() {
        mockMvc
            .perform(
                post("/api/v1/public-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"localhost"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_DOMAIN"))
    }

    @Test
    fun `a blank domain fails bean validation`() {
        mockMvc
            .perform(
                post("/api/v1/public-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"   "}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a fresh completed scan reuses the crawl but hands the visitor a new per-visitor result`() {
        val cached =
            publicScanRepository.save(
                PublicScanEntity(
                    domain = "cached.example",
                    status = ScanStatus.DONE,
                    publicToken = "tok_cached_${UUID.randomUUID()}",
                    createdAt = Instant.now().minus(Duration.ofMinutes(10)),
                    updatedAt = Instant.now().minus(Duration.ofMinutes(10)),
                    expiresAt = Instant.now().plus(Duration.ofDays(7)),
                ),
            )
        publicScanCookieRepository.save(
            PublicScanCookieEntity(
                publicScanId = cached.id,
                name = "_ga",
                category = "statistics",
                provider = "Google Analytics",
                isKnown = true,
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/public-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"cached.example"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("done"))
            // A fresh per-visitor token, never the shared cached one.
            .andExpect(jsonPath("$.data.token").value(not(cached.publicToken)))

        // A second row was materialized (the per-visitor copy) rather than reusing the cached identity.
        val rows = publicScanRepository.findAll()
        assertEquals(2, rows.size)
        val copy = rows.single { it.id != cached.id }
        assertEquals(ScanStatus.DONE, copy.status)
        assertEquals("cached.example", copy.domain)

        // The copy carries the crawl's cookies re-keyed to the new row; the original's are untouched.
        val copiedCookies = publicScanCookieRepository.findByPublicScanId(copy.id)
        assertEquals(1, copiedCookies.size)
        assertTrue(copiedCookies.single().let { it.name == "_ga" && it.category == "statistics" })
        assertEquals(1, publicScanCookieRepository.findByPublicScanId(cached.id).size)
    }
}
