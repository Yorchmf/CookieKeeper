package com.complyr.notify

import com.complyr.common.ComplyrProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/**
 * Transactional email via Brevo's HTTP API (`POST /v3/smtp/email`). Brevo is an EU processor, so this
 * keeps mail delivery on EU infrastructure (CLAUDE.md constraint #2). Selected in dev/prd by
 * `complyr.mail.provider=brevo`; locally [SmtpEmailSender] delivers to Mailpit instead. Exactly one of
 * the two beans is created — see the mutually exclusive `@ConditionalOnProperty` on each.
 *
 * The API key authenticates via the `api-key` header (never logged). A blank key is rejected at
 * startup so a `provider=brevo` environment with `BREVO_API_KEY` unset fails fast rather than silently
 * dropping every email. Any transport or non-2xx failure is wrapped in [EmailDeliveryException]; the
 * calling [AuthNotifier]/[BillingNotifier] treat delivery as best-effort and never propagate it.
 */
@Service
@ConditionalOnProperty(prefix = "complyr.mail", name = ["provider"], havingValue = "brevo")
class BrevoEmailSender(
    restClientBuilder: RestClient.Builder,
    properties: ComplyrProperties,
) : EmailSender {
    private val apiKey = properties.mail.brevo.apiKey
    private val sender = BrevoSender(properties.mail.brevo.senderName, properties.mailFrom)

    // The injected builder is supplied by [BrevoRestClientConfig] with connect/read timeouts already
    // bound, so this sender stays a thin consumer (and MockRestServiceServer can still swap the request
    // factory under test). Timeouts are essential: an infinite default would let a stalled Brevo
    // endpoint pin the tiny mail executor (corePoolSize=1) forever and silently halt all mail.
    private val restClient = restClientBuilder.baseUrl(properties.mail.brevo.baseUrl).build()

    init {
        require(apiKey.isNotBlank()) {
            "complyr.mail.brevo.api-key (BREVO_API_KEY) must be set when complyr.mail.provider=brevo"
        }
    }

    // SwallowedException: the response-error cause is dropped on purpose — its message can carry PII.
    @Suppress("SwallowedException")
    override fun send(
        to: String,
        subject: String,
        htmlBody: String,
        replyTo: String?,
    ) {
        val request =
            BrevoSendRequest(
                sender = sender,
                to = listOf(BrevoRecipient(to)),
                subject = subject,
                htmlContent = htmlBody,
                // Omitted from the JSON when null (Jackson default) so transactional mail is byte-for-byte
                // unchanged; only the contact form populates it.
                replyTo = replyTo?.let { BrevoReplyTo(it) },
            )
        try {
            restClient
                .post()
                .uri(TRANSACTIONAL_EMAIL_PATH)
                .header(API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity()
        } catch (ex: RestClientResponseException) {
            // A non-2xx response exception's message embeds Brevo's response body, which can echo the
            // recipient address (PII, CLAUDE.md constraint #4). Keep only the status code and drop the
            // body-bearing cause so best-effort logging can never surface it.
            throw EmailDeliveryException("Brevo delivery failed with HTTP ${ex.statusCode.value()}")
        } catch (ex: RestClientException) {
            // Transport failures (connect/read timeout, DNS) — messages are our-side and carry no
            // recipient/subject, so the cause is safe to chain for diagnosis.
            throw EmailDeliveryException("Brevo delivery failed", ex)
        }
    }

    private companion object {
        const val TRANSACTIONAL_EMAIL_PATH = "/v3/smtp/email"
        const val API_KEY_HEADER = "api-key"
    }
}

/**
 * Brevo `POST /v3/smtp/email` request body (field names match the API verbatim).
 * `NON_NULL` so a transactional (null-[replyTo]) send emits exactly the previous payload — the field
 * is dropped rather than sent as `"replyTo":null`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
private data class BrevoSendRequest(
    val sender: BrevoSender,
    val to: List<BrevoRecipient>,
    val subject: String,
    val htmlContent: String,
    val replyTo: BrevoReplyTo? = null,
)

private data class BrevoSender(
    val name: String,
    val email: String,
)

private data class BrevoRecipient(
    val email: String,
)

private data class BrevoReplyTo(
    val email: String,
)
