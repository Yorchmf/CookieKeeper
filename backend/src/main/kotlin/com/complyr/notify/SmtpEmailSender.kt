package com.complyr.notify

import com.complyr.common.ComplyrProperties
import jakarta.mail.MessagingException
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

/** SMTP delivery (Mailpit locally, Brevo relay in dev/prd). */
@Service
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    private val properties: ComplyrProperties,
) : EmailSender {
    override fun send(
        to: String,
        subject: String,
        htmlBody: String,
    ) {
        try {
            val message = mailSender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, Charsets.UTF_8.name())
            helper.setFrom(properties.mailFrom)
            helper.setTo(to)
            helper.setSubject(subject)
            helper.setText(htmlBody, true)
            mailSender.send(message)
        } catch (ex: MailException) {
            throw EmailDeliveryException("SMTP delivery failed", ex)
        } catch (ex: MessagingException) {
            throw EmailDeliveryException("Failed to build email message", ex)
        }
    }
}
