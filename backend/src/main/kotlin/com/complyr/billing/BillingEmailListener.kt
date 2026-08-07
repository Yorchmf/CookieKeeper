package com.complyr.billing

import com.complyr.common.AsyncConfig
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Dispatches billing lifecycle emails AFTER the webhook transaction commits and asynchronously on
 * the shared mail executor. [BillingNotifier] guarantees failures never propagate, so a broken
 * mail provider can only ever cost a log line — never a redelivered webhook.
 */
@Component
class BillingEmailListener(
    private val notifier: BillingNotifier,
) {
    @Async(AsyncConfig.EMAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onBillingEmailRequested(event: BillingEmailRequested) {
        when (event) {
            is SubscriptionActivated -> notifier.sendSubscriptionActivated(event.userId, event.plan)
            is PaymentIssue -> notifier.sendPaymentIssue(event.userId)
            is TrialEnding -> notifier.sendTrialEnding(event.userId)
        }
    }
}
