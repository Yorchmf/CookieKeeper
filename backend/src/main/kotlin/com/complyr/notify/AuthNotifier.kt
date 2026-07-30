package com.complyr.notify

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Composes and delivers auth emails. Delivery is best-effort: failures are logged
 * (userId only — never email addresses, no PII in logs) and never propagate, so a
 * broken SMTP relay can never fail a signup or password-reset transaction.
 */
@Service
class AuthNotifier(
    private val composer: AuthEmailComposer,
    private val sender: EmailSender,
) {
    private val log = LoggerFactory.getLogger(AuthNotifier::class.java)

    fun sendVerification(
        userId: UUID,
        email: String,
        locale: String,
        rawToken: String,
    ) {
        sendQuietly(userId, email, composer.verificationEmail(locale, rawToken))
    }

    fun sendPasswordReset(
        userId: UUID,
        email: String,
        locale: String,
        rawToken: String,
    ) {
        sendQuietly(userId, email, composer.passwordResetEmail(locale, rawToken))
    }

    @Suppress("TooGenericExceptionCaught") // the "never propagate" contract must survive any sender bug
    private fun sendQuietly(
        userId: UUID,
        email: String,
        composed: ComposedEmail,
    ) {
        try {
            sender.send(email, composed.subject, composed.htmlBody)
        } catch (ex: Exception) {
            // Broad on purpose: no sender failure of any kind may ever propagate.
            log.error("Failed to send auth email to user {}", userId, ex)
        }
    }
}
