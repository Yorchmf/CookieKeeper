package com.complyr.billing

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.RecordingEmailConfig
import com.complyr.auth.RecordingEmailSender
import com.complyr.auth.UserRepository
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * `GET /api/v1/billing/entitlement` — the dashboard billing read. Covers the authenticated envelope for
 * a fresh (cardless-trial) account and for one with an active paid subscription, plus the 401 for an
 * anonymous caller. Subscriptions are seeded directly since Stripe checkout isn't exercised here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RecordingEmailConfig::class)
class BillingApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var emailSender: RecordingEmailSender

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
                stripeCustomerId = "cus_${UUID.randomUUID()}",
                stripeSubId = "sub_${UUID.randomUUID()}",
                plan = plan,
                status = "active",
                periodEnd = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `a fresh account reads a trial entitlement with the starter site cap`() {
        val user = registeredUser()

        mockMvc
            .perform(get("/api/v1/billing/entitlement").cookie(user))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state").value("trial"))
            .andExpect(jsonPath("$.data.plan").doesNotExist())
            .andExpect(jsonPath("$.data.trialEndsAt").isNotEmpty)
            .andExpect(jsonPath("$.data.activeSites").value(0))
            .andExpect(jsonPath("$.data.limits.maxSites").value(1))
            .andExpect(jsonPath("$.data.limits.csvExport").value(false))
    }

    @Test
    fun `a subscribed account reads its plan and unlocked limits`() {
        val user = registeredUser(Plan.BUSINESS)

        mockMvc
            .perform(get("/api/v1/billing/entitlement").cookie(user))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.state").value("subscribed"))
            .andExpect(jsonPath("$.data.plan").value("BUSINESS"))
            .andExpect(jsonPath("$.data.trialEndsAt").doesNotExist())
            .andExpect(jsonPath("$.data.limits.maxSites").value(10))
            .andExpect(jsonPath("$.data.limits.csvExport").value(true))
            .andExpect(jsonPath("$.data.limits.consentRetentionMonths").value(36))
    }

    @Test
    fun `the entitlement read requires authentication`() {
        mockMvc
            .perform(get("/api/v1/billing/entitlement"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
    }
}
