package com.complyr.notify

import com.complyr.common.AsyncConfig
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Dispatches auth emails AFTER the publishing transaction commits and asynchronously on
 * the dedicated mail executor. [AuthNotifier] guarantees failures never propagate, so a
 * broken relay can only ever cost a log line.
 */
@Component
class AuthEmailListener(
    private val notifier: AuthNotifier,
) {
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAuthEmailRequested(event: AuthEmailRequested) {
        when (event) {
            is VerificationEmailRequested ->
                notifier.sendVerification(event.userId, event.email, event.locale, event.rawToken)
            is PasswordResetEmailRequested ->
                notifier.sendPasswordReset(event.userId, event.email, event.locale, event.rawToken)
            is WelcomeEmailRequested ->
                notifier.sendWelcome(event.userId, event.email, event.locale)
        }
    }
}
