package com.complyr.billing

import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.notify.BestEffortEmailDelivery
import com.complyr.notify.ComposedEmail
import com.complyr.notify.EmailSender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class BillingNotifierTest {
    private val composer = mockk<BillingEmailComposer>()
    private val sender = mockk<EmailSender>()
    private val userRepository = mockk<UserRepository>()
    private val notifier = BillingNotifier(composer, BestEffortEmailDelivery(sender), userRepository)

    private val userId: UUID = UUID.randomUUID()

    private fun user(locale: String = "de") =
        UserEntity(
            id = userId,
            email = "alice@example.com",
            passwordHash = "hash",
            locale = locale,
            createdAt = Instant.parse("2026-08-01T12:00:00Z"),
            verifiedAt = Instant.parse("2026-08-01T12:00:00Z"),
        )

    @Test
    fun `resolves the user and delivers the activated email in their locale`() {
        every { userRepository.findById(userId) } returns Optional.of(user(locale = "de"))
        every { composer.subscriptionActivatedEmail("de", Plan.PRO) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs

        notifier.sendSubscriptionActivated(userId, Plan.PRO)

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `resolves the user and delivers the payment-issue email in their locale`() {
        every { userRepository.findById(userId) } returns Optional.of(user(locale = "fr"))
        every { composer.paymentIssueEmail("fr") } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs

        notifier.sendPaymentIssue(userId)

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `sends nothing when the user no longer exists`() {
        every { userRepository.findById(userId) } returns Optional.empty()

        notifier.sendSubscriptionActivated(userId, Plan.BUSINESS)
        notifier.sendPaymentIssue(userId)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }
}
