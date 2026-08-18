package eu.cookiekeeper.notify

/** Thrown by [EmailSender] implementations; callers decide whether delivery is critical. */
class EmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Transactional email boundary. Local/dev deliver via SMTP (Mailpit/Brevo);
 * tests swap in a recording fake.
 */
interface EmailSender {
    /**
     * @param replyTo optional Reply-To address. Transactional mail leaves it null (the From address is
     *   the reply target); the support contact form sets it to the submitting customer's address so a
     *   reply from the support inbox reaches them directly. It is a recipient-controlled value, so it is
     *   only ever a header/JSON field — never interpolated into a body — and callers validate it first.
     * @throws EmailDeliveryException when the message cannot be handed to the mail system.
     */
    fun send(
        to: String,
        subject: String,
        htmlBody: String,
        replyTo: String? = null,
    )
}
