package eu.cookiekeeper.site

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.RecordingEmailConfig
import eu.cookiekeeper.auth.RecordingEmailSender
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.scan.ScanTargetValidator
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Stub for the outbound half of verification: whatever a test sets is what the "customer's homepage"
 * returns. Subclassing rather than mocking keeps the real [SiteVerificationService] wiring intact
 * while guaranteeing no test ever opens a socket — the production path is covered by
 * [SiteVerificationFetcherIntegrationTest] against a local HTTP server.
 */
class StubVerificationFetcher(
    validator: ScanTargetValidator,
    properties: CookieKeeperProperties,
    clock: Clock,
) : SiteVerificationFetcher(validator, properties, clock) {
    /** Body handed back for the next fetch; null means "could not be read", as in production. */
    var html: String? = null

    /** How many times the homepage was actually dialled — the assertion behind idempotency. */
    val fetches = AtomicInteger()

    override fun fetchHomepage(domain: String): String? {
        fetches.incrementAndGet()
        return html
    }
}

/** DNS seam ([DnsTxtLookup.TxtResolver]) as a bean, so the fallback path is drivable from a test. */
class StubTxtResolver : DnsTxtLookup.TxtResolver {
    var records: List<String> = emptyList()

    override fun lookupTxt(
        name: String,
        timeoutMillis: Long,
    ): List<String> = records
}

@TestConfiguration(proxyBeanMethods = false)
class StubVerificationConfig {
    @Bean
    @Primary
    fun stubVerificationFetcher(
        validator: ScanTargetValidator,
        properties: CookieKeeperProperties,
        clock: Clock,
    ): StubVerificationFetcher = StubVerificationFetcher(validator, properties, clock)

    @Bean
    fun stubTxtResolver(): StubTxtResolver = StubTxtResolver()

    @Bean
    @Primary
    fun stubDnsTxtLookup(
        properties: CookieKeeperProperties,
        resolver: StubTxtResolver,
    ): DnsTxtLookup = DnsTxtLookup(properties, resolver)
}

/**
 * End-to-end domain verification over the real HTTP surface: ownership isolation, the two proofs, the
 * 200-with-`verified:false` contract, and the invariant that changing the domain revokes verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class, StubVerificationConfig::class)
class SiteVerificationApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var fetcher: StubVerificationFetcher

    @Autowired
    private lateinit var txtResolver: StubTxtResolver

    @BeforeEach
    fun resetStubs() {
        fetcher.html = null
        fetcher.fetches.set(0)
        txtResolver.records = emptyList()
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

    /** Creates a site and returns its id and the detail payload's site key. */
    private fun createSite(cookie: Cookie): Pair<String, String> {
        val domain = "site-${UUID.randomUUID().toString().take(8)}.example.com"
        val result =
            mockMvc
                .perform(
                    post("/api/v1/sites")
                        .cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"domain":"$domain"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
        val data = objectMapper.readTree(result.response.contentAsString).path("data")
        return data.path("id").asString() to data.path("siteKey").asString()
    }

    private fun snippetFor(siteKey: String): String =
        """<!doctype html><html><head><script async src="http://localhost:8081/v1.js" data-complyr="$siteKey">""" +
            """</script></head><body>hi</body></html>"""

    @Test
    fun `snippet verification flips the site detail payload`() {
        val alice = registeredUser()
        val (siteId, siteKey) = createSite(alice)

        // Before: unverified, and the detail response already carries the DNS instructions so the UI
        // can render both methods without a second round trip.
        val detail =
            mockMvc
                .perform(get("/api/v1/sites/$siteId").cookie(alice))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.verifiedAt").doesNotExist())
                .andExpect(jsonPath("$.data.verificationMethod").doesNotExist())
                .andExpect(jsonPath("$.data.dnsRecordValue").value(siteKey))
                .andReturn()
        val dnsRecordName =
            objectMapper
                .readTree(detail.response.contentAsString)
                .path("data")
                .path("dnsRecordName")
                .asString()
        // The instruction the customer follows must be exactly the name DnsTxtLookup queries.
        assertEquals("_cookiekeeper.", dnsRecordName.take("_cookiekeeper.".length))

        fetcher.html = snippetFor(siteKey)
        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verified").value(true))
            .andExpect(jsonPath("$.data.method").value("snippet"))

        mockMvc
            .perform(get("/api/v1/sites/$siteId").cookie(alice))
            .andExpect(jsonPath("$.data.verifiedAt").isNotEmpty)
            .andExpect(jsonPath("$.data.verificationMethod").value("snippet"))

        // Verifying again is idempotent and — crucially — makes no second outbound request.
        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verified").value(true))
        assertEquals(1, fetcher.fetches.get())
    }

    @Test
    fun `the DNS TXT record verifies a site whose homepage carries no snippet`() {
        val alice = registeredUser()
        val (siteId, siteKey) = createSite(alice)
        fetcher.html = "<html><body>no snippet here</body></html>"
        txtResolver.records = listOf("\"$siteKey\"")

        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verified").value(true))
            .andExpect(jsonPath("$.data.method").value("dns_txt"))
    }

    @Test
    fun `a miss is a 200 with a reason, not an error`() {
        val alice = registeredUser()
        val (siteId, _) = createSite(alice)

        // Homepage readable, no proof on it: actionable "install the snippet" advice.
        fetcher.html = "<html><body>nothing yet</body></html>"
        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.verified").value(false))
            .andExpect(jsonPath("$.data.reason").value("snippet_not_found"))
            .andExpect(jsonPath("$.error").doesNotExist())

        // Homepage unreadable — every underlying cause collapses to one indistinguishable answer.
        fetcher.html = null
        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verified").value(false))
            .andExpect(jsonPath("$.data.reason").value("unreachable"))

        // A miss leaves the site untouched.
        mockMvc
            .perform(get("/api/v1/sites/$siteId").cookie(alice))
            .andExpect(jsonPath("$.data.verifiedAt").doesNotExist())
    }

    @Test
    fun `changing the domain revokes verification`() {
        // The one-way latch is only safe while it tracks the domain it was proved against — otherwise a
        // customer could verify a domain they own, then repoint the site at one they do not.
        val alice = registeredUser()
        val (siteId, siteKey) = createSite(alice)
        fetcher.html = snippetFor(siteKey)
        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(alice))
            .andExpect(jsonPath("$.data.verified").value(true))

        mockMvc
            .perform(
                patch("/api/v1/sites/$siteId")
                    .cookie(alice)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"moved-${UUID.randomUUID().toString().take(8)}.example.com"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.verifiedAt").doesNotExist())
            .andExpect(jsonPath("$.data.verificationMethod").doesNotExist())
    }

    @Test
    fun `user B cannot verify user A's site, and gets a 404 rather than a 403`() {
        val alice = registeredUser()
        val bob = registeredUser()
        val (siteId, siteKey) = createSite(alice)
        fetcher.html = snippetFor(siteKey)

        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))

        // Not merely refused: no outbound request was made on a stranger's behalf.
        assertEquals(0, fetcher.fetches.get())

        mockMvc
            .perform(post("/api/v1/sites/$siteId/verify"))
            .andExpect(status().isUnauthorized)
    }
}
