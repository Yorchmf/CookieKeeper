package eu.cookiekeeper.notify

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Hands one composed email to the [EmailSender], swallowing every failure. Delivery of
 * transactional email (auth, billing) is best-effort: a broken mail provider must never fail
 * the business transaction that requested the mail. Failures log the [userId] only — never the
 * email address or any other PII (CLAUDE.md constraint #4) — and never propagate.
 *
 * The domain notifiers ([AuthNotifier], [eu.cookiekeeper.billing.BillingNotifier]) share this single
 * contract so the "never propagate, log userId only" guarantee lives in one place.
 */
@Component
class BestEffortEmailDelivery(
    private val sender: EmailSender,
) {
    private val log = LoggerFactory.getLogger(BestEffortEmailDelivery::class.java)

    @Suppress("TooGenericExceptionCaught") // the "never propagate" contract must survive any sender bug
    fun deliver(
        userId: UUID,
        email: String,
        composed: ComposedEmail,
        kind: String,
    ) {
        try {
            sender.send(email, composed.subject, composed.htmlBody)
        } catch (ex: Exception) {
            // Broad on purpose: no sender failure of any kind may ever propagate. Logging the throwable
            // is PII-safe because every EmailSender throws EmailDeliveryException with a curated,
            // recipient-free message and never chains a cause whose message carries PII (see the Brevo
            // and SMTP senders); stack frames themselves expose no addresses.
            log.error("Failed to send {} email to user {}", kind, userId, ex)
        }
    }
}
