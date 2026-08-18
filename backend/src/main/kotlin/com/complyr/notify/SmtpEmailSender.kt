package com.complyr.notify

import com.complyr.common.ComplyrProperties
import jakarta.mail.MessagingException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

/**
 * SMTP delivery — Mailpit locally. The default [EmailSender]: active unless `complyr.mail.provider`
 * selects another (`brevo` → [BrevoEmailSender]), so a missing property still yields SMTP. Exactly one
 * sender bean is created.
 */
@Service
@ConditionalOnProperty(prefix = "complyr.mail", name = ["provider"], havingValue = "smtp", matchIfMissing = true)
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    private val properties: ComplyrProperties,
) : EmailSender {
    // SwallowedException: the MailException cause is dropped on purpose — its message can carry PII.
    @Suppress("SwallowedException")
    override fun send(
        to: String,
        subject: String,
        htmlBody: String,
        replyTo: String?,
    ) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, Charsets.UTF_8.name())
            helper.setFrom(properties.mailFrom)
            helper.setTo(to)
            replyTo?.let { helper.setReplyTo(it) }
            helper.setSubject(subject)
            helper.setText(htmlBody, true)
            mailSender.send(message)
        } catch (ex: MailException) {
            // MailSendException can embed the SMTP server's bounce text, which may echo the recipient
            // address (PII, CLAUDE.md constraint #4). Keep the exception type as a hint but drop the
            // body-bearing cause so best-effort logging can never surface it.
            throw EmailDeliveryException("SMTP delivery failed (${ex.javaClass.simpleName})")
        } catch (ex: MessagingException) {
            // Message-construction failure — our-side, carries no recipient/subject, safe to chain.
            throw EmailDeliveryException("Failed to build email message", ex)
        }
    }
}
