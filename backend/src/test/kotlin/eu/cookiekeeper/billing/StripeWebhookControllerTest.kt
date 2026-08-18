package eu.cookiekeeper.billing

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.common.CookieKeeperProperties
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
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertNotNull

/**
 * Controller-path tests for `POST /api/v1/billing/webhook` — the unauthenticated, signature-verified
 * Stripe endpoint. [BillingWebhookServiceTest] fakes the gateway, so the load-bearing HTTP-boundary
 * controls are only exercised here against the real filter chain + gateway:
 *  - the 256 KB body cap (a DoS brake on an unauthenticated, rate-limit-exempt endpoint) rejects an
 *    oversize body with 413 BEFORE any parse;
 *  - a missing / tampered signature is rejected with 400 (never told to retry an unsatisfiable body);
 *  - a validly-signed body round-trips through [StripeWebhookController.readCappedBody]'s raw UTF-8
 *    read with its HMAC intact → 200 and recorded in the inbox (a regression here would break EVERY
 *    real webhook, silently).
 *
 * The signing secret is the test `cookiekeeper.billing.webhook-secret`; [stripeSignature] reproduces
 * Stripe's `t=<ts>,v1=<hmac-sha256>` scheme so a genuine signed request can be asserted end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StripeWebhookControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var properties: CookieKeeperProperties

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var stripeEventRepository: StripeEventRepository

    @Test
    fun `an oversize body is rejected with 413 before any signature check`() {
        // One chunk past the 256 KB cap; a real event is a few KB. The (bogus) signature is never even
        // reached — the cap must fire first, bounding what an unauthenticated caller can push in memory.
        val oversize = "x".repeat(MAX_PAYLOAD_BYTES + 1024)

        mockMvc
            .perform(
                post("/api/v1/billing/webhook")
                    .header(STRIPE_SIGNATURE_HEADER, "t=1,v1=deadbeef")
                    .content(oversize),
            ).andExpect(status().isPayloadTooLarge)
            .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun `a missing signature header is rejected with 400 not 500`() {
        mockMvc
            .perform(
                post("/api/v1/billing/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"))
    }

    @Test
    fun `a tampered body fails signature verification with 400`() {
        val payload = eventJson()
        val timestamp = Instant.now().epochSecond
        val header = stripeSignature(payload, timestamp)

        mockMvc
            .perform(
                post("/api/v1/billing/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(STRIPE_SIGNATURE_HEADER, header)
                    // Mutate the body AFTER signing, so the HMAC over the raw bytes no longer matches.
                    .content("$payload "),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_SIGNATURE"))
    }

    @Test
    fun `a validly-signed event is accepted with 200 and recorded in the inbox`() {
        val eventId = "evt_ctrl_${UUID.randomUUID()}"
        val payload = eventJson(eventId)
        val timestamp = Instant.now().epochSecond
        try {
            mockMvc
                .perform(
                    post("/api/v1/billing/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(STRIPE_SIGNATURE_HEADER, stripeSignature(payload, timestamp))
                        .content(payload),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.received").value(true))

            // A non-subscription ("Ignored") event changes no billing state but is still durably
            // recorded — proof the raw body survived the capped read with its signature verifiable.
            assertNotNull(
                stripeEventRepository.findByStripeEventId(eventId),
                "the validly-signed event is recorded in the stripe_events inbox",
            )
        } finally {
            // stripe_events has no append-only trigger (unlike consent_events), so the shared container
            // is left clean for other tests.
            jdbcTemplate.update("DELETE FROM stripe_events WHERE stripe_event_id = ?", eventId)
        }
    }

    /** A minimal but GSON-parseable Stripe event whose type is not `customer.subscription.*` (→ Ignored). */
    private fun eventJson(id: String = "evt_ctrl_${UUID.randomUUID()}"): String =
        """{"id":"$id","object":"event","api_version":"2024-06-20","created":${Instant.now().epochSecond},""" +
            """"type":"invoice.paid","data":{"object":{"id":"in_1","object":"invoice"}}}"""

    /** Reproduce Stripe's webhook signature header (`t=<ts>,v1=<hex HMAC-SHA256 of "ts.payload">`). */
    private fun stripeSignature(
        payload: String,
        timestamp: Long,
    ): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(properties.billing.webhookSecret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
        val signature =
            mac
                .doFinal("$timestamp.$payload".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$signature"
    }

    private companion object {
        const val STRIPE_SIGNATURE_HEADER = "Stripe-Signature"
        const val HMAC_SHA256 = "HmacSHA256"

        // Mirror of StripeWebhookController.MAX_PAYLOAD_BYTES (256 KB); kept in sync deliberately.
        const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}
