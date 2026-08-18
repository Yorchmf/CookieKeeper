package eu.cookiekeeper.notify

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Composes and delivers auth emails via the shared [BestEffortEmailDelivery] contract: failures
 * are logged (userId only — never email addresses, no PII in logs) and never propagate, so a
 * broken mail provider can never fail a signup or password-reset transaction.
 */
@Service
class AuthNotifier(
    private val composer: AuthEmailComposer,
    private val delivery: BestEffortEmailDelivery,
) {
    fun sendVerification(
        userId: UUID,
        email: String,
        locale: String,
        rawToken: String,
    ) {
        delivery.deliver(userId, email, composer.verificationEmail(locale, rawToken), "verification")
    }

    fun sendPasswordReset(
        userId: UUID,
        email: String,
        locale: String,
        rawToken: String,
    ) {
        delivery.deliver(userId, email, composer.passwordResetEmail(locale, rawToken), "password-reset")
    }

    fun sendWelcome(
        userId: UUID,
        email: String,
        locale: String,
    ) {
        delivery.deliver(userId, email, composer.welcomeEmail(locale), "welcome")
    }

    fun sendEmailChange(
        userId: UUID,
        email: String,
        locale: String,
        rawToken: String,
    ) {
        delivery.deliver(userId, email, composer.emailChangeEmail(locale, rawToken), "email-change")
    }

    fun sendEmailChangedNotice(
        userId: UUID,
        email: String,
        locale: String,
    ) {
        delivery.deliver(userId, email, composer.emailChangedNoticeEmail(locale), "email-changed-notice")
    }
}
