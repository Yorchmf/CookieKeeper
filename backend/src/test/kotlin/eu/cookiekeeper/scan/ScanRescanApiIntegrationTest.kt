package eu.cookiekeeper.scan

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
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * `POST /api/v1/sites/{siteId}/scans` — the "Re-scan now" action. Covers the plan gate, the
 * one-live-scan-per-site throttle, and ownership isolation over the real HTTP surface.
 *
 * Every test has to clear the scan the site-creation flow enqueues first: that queued row is itself a
 * live scan, so without [completeLiveScans] a fresh site would answer every re-scan request with a 409.
 * That is the feature working, not a fixture quirk — which the first test asserts directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class ScanRescanApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var scanRepository: ScanRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var subscriptionRepository: SubscriptionRepository

    private fun registeredUser(plan: Plan? = null): Cookie {
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
        if (plan != null) grantSubscription(email, plan)
        return Cookie("cmplyr_at", accessHeader.substringAfter("=").substringBefore(";"))
    }

    private fun grantSubscription(
        email: String,
        plan: Plan,
    ) {
        val userId = requireNotNull(userRepository.findByEmail(email)).id
        val now = Instant.now()
        subscriptionRepository.saveAndFlush(
            SubscriptionEntity(
                userId = userId,
                stripeCustomerId = null,
                stripeSubId = null,
                plan = plan,
                status = "active",
                periodEnd = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun createSite(cookie: Cookie): UUID {
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
        return UUID.fromString(
            objectMapper
                .readTree(result.response.contentAsString)
                .path("data")
                .path("id")
                .asString(),
        )
    }

    /** Land every live scan of [siteId], standing in for the scanner worker (not running in this context). */
    private fun completeLiveScans(siteId: UUID) {
        val now = Instant.now()
        scanRepository
            .findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, PAGE_SIZE))
            .filter { it.status in ScanRequestService.LIVE_STATUSES }
            .forEach { scanRepository.save(it.copy(status = ScanStatus.DONE, finishedAt = now, updatedAt = now)) }
    }

    private fun liveScanCount(siteId: UUID): Int =
        scanRepository
            .findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, PAGE_SIZE))
            .count { it.status in ScanRequestService.LIVE_STATUSES }

    @Test
    fun `an entitled account queues a manual re-scan, and a second request while it runs is a 409`() {
        val alice = registeredUser(Plan.PRO)
        val siteId = createSite(alice)

        // The scan enqueued at site creation is already live, so re-scan is refused until it lands.
        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SCAN_ALREADY_IN_PROGRESS"))

        completeLiveScans(siteId)
        val created =
            mockMvc
                .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("queued"))
                .andReturn()
        val scanId =
            UUID.fromString(
                objectMapper
                    .readTree(created.response.contentAsString)
                    .path("data")
                    .path("scanId")
                    .asString(),
            )
        val scan = assertNotNull(scanRepository.findByIdAndSiteId(scanId, siteId))
        assertEquals(ScanTrigger.MANUAL, scan.trigger)
        assertEquals(ScanStatus.QUEUED, scan.status)

        // Immediately again: still exactly one live scan — the throttle is structural, not a counter.
        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("SCAN_ALREADY_IN_PROGRESS"))
        assertEquals(1, liveScanCount(siteId))
    }

    @Test
    fun `a trial account is refused with the upgrade code, and no scan is queued`() {
        val alice = registeredUser()
        val siteId = createSite(alice)
        completeLiveScans(siteId)

        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ON_DEMAND_RESCAN_NOT_ENTITLED"))
        assertEquals(0, liveScanCount(siteId))
    }

    @Test
    fun `the plan gate is answered before scan state, so a busy site still reads as not entitled`() {
        // Otherwise the 409 would tell a Starter user the feature would have worked — an inconsistent
        // prompt that depends on timing rather than on their plan.
        val alice = registeredUser()
        val siteId = createSite(alice)

        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ON_DEMAND_RESCAN_NOT_ENTITLED"))
    }

    @Test
    fun `user B cannot re-scan user A's site, and gets a 404 rather than a 403`() {
        val alice = registeredUser(Plan.PRO)
        val bob = registeredUser(Plan.PRO)
        val siteId = createSite(alice)
        completeLiveScans(siteId)

        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(bob))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
        assertEquals(0, liveScanCount(siteId))

        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `an archived site cannot be re-scanned`() {
        val alice = registeredUser(Plan.PRO)
        val siteId = createSite(alice)
        completeLiveScans(siteId)
        mockMvc.perform(delete("/api/v1/sites/$siteId").cookie(alice)).andExpect(status().isOk)

        mockMvc
            .perform(post("/api/v1/sites/$siteId/scans").cookie(alice))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("SITE_NOT_FOUND"))
        assertEquals(0, liveScanCount(siteId))
    }

    private companion object {
        // Comfortably above the handful of scans any single test creates.
        const val PAGE_SIZE = 50
    }
}
