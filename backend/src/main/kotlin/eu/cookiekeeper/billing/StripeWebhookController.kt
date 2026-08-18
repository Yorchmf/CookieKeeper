package eu.cookiekeeper.billing

import eu.cookiekeeper.common.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream

/**
 * The unauthenticated, signature-verified Stripe webhook endpoint (permit-listed in
 * [eu.cookiekeeper.common.SecurityConfig]). Stripe cannot present a JWT, so authenticity rests entirely
 * on the per-request signature verified in [StripeGateway.parseWebhookEvent] plus the body-size cap
 * below — this endpoint trusts nothing about the caller until the signature checks out.
 *
 * The handler is deliberately thin: read the body under a hard size cap, hand the RAW bytes
 * (verbatim, so the signature still matches) and the signature header to [BillingWebhookService],
 * and always answer with the standard 200 envelope on success. Any failure other than a bad
 * signature (which [WebhookSignatureException] maps to 400) is recorded durably and still returns
 * 200 so Stripe stops retrying an event we've captured; genuine apply failures roll back and are
 * retried via redelivery.
 */
@RestController
@RequestMapping("/api/v1/billing")
class StripeWebhookController(
    private val billingWebhookService: BillingWebhookService,
) {
    @PostMapping("/webhook", consumes = [MediaType.ALL_VALUE])
    fun webhook(
        request: HttpServletRequest,
        @RequestHeader(value = STRIPE_SIGNATURE_HEADER, required = false) signature: String?,
    ): ApiResponse<WebhookAck> {
        // Read (under the size cap) before the header check so an oversize body is rejected with 413
        // regardless of whether a signature is present. The header is bound as optional so an absent
        // one becomes our own 400 (INVALID_SIGNATURE) instead of Spring's unmapped 500 — a missing
        // signature is a bad request Stripe must not be told to retry, same as an invalid one.
        val payload = readCappedBody(request)
        if (signature.isNullOrBlank()) throw WebhookSignatureException()
        billingWebhookService.handle(payload, signature)
        return ApiResponse.success(WebhookAck(received = true))
    }

    /**
     * Read the request body into a String, aborting the moment it exceeds [MAX_PAYLOAD_BYTES]. We
     * read the raw servlet stream ourselves instead of binding `@RequestBody String`, whose message
     * converter would materialize the ENTIRE body into memory before any size check could run — an
     * OOM lever for an unauthenticated, rate-limit-exempt endpoint. Here at most one extra chunk
     * beyond the cap is ever buffered. Bytes are decoded verbatim as UTF-8 so the HMAC over the raw
     * body still verifies downstream.
     */
    private fun readCappedBody(request: HttpServletRequest): String {
        val buffer = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val chunk = ByteArray(READ_CHUNK_BYTES)
        request.inputStream.use { stream ->
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read == -1) break
                total += read
                // Reject before buffering the overflowing chunk — the cap bounds what we hold.
                if (total > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException()
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toString(Charsets.UTF_8)
    }

    /** Minimal ack body; the envelope's `success` is what Stripe keys on, not this field. */
    data class WebhookAck(
        val received: Boolean,
    )

    private companion object {
        /** Stripe's signature header carrying the timestamp + HMAC scheme verified by the SDK. */
        const val STRIPE_SIGNATURE_HEADER = "Stripe-Signature"

        // 256 KB: two orders of magnitude above a real subscription event, so it only clips abuse.
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        /** Read granularity; also the most we can over-buffer past the cap before rejecting. */
        const val READ_CHUNK_BYTES = 8 * 1024

        /** Initial buffer sizing for a typical (few-KB) event, to avoid early doubling. */
        const val INITIAL_BUFFER_BYTES = 16 * 1024
    }
}
