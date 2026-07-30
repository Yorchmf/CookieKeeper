package com.complyr.notify

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
    /** @throws EmailDeliveryException when the message cannot be handed to the mail system. */
    fun send(
        to: String,
        subject: String,
        htmlBody: String,
    )
}
