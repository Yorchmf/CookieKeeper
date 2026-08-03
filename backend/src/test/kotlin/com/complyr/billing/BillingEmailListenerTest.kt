package com.complyr.billing

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class BillingEmailListenerTest {
    private val notifier = mockk<BillingNotifier>()
    private val listener = BillingEmailListener(notifier)
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `subscription-activated events are dispatched to the notifier`() {
        every { notifier.sendSubscriptionActivated(userId, Plan.PRO) } just runs

        listener.onBillingEmailRequested(SubscriptionActivated(userId, Plan.PRO))

        verify(exactly = 1) { notifier.sendSubscriptionActivated(userId, Plan.PRO) }
    }

    @Test
    fun `payment-issue events are dispatched to the notifier`() {
        every { notifier.sendPaymentIssue(userId) } just runs

        listener.onBillingEmailRequested(PaymentIssue(userId))

        verify(exactly = 1) { notifier.sendPaymentIssue(userId) }
    }
}
