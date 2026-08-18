package eu.cookiekeeper.notify

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class BestEffortEmailDeliveryTest {
    private val sender = mockk<EmailSender>()
    private val delivery = BestEffortEmailDelivery(sender)

    @Test
    fun `hands the composed email to the sender`() {
        every { sender.send(any(), any(), any()) } just runs

        delivery.deliver(UUID.randomUUID(), "alice@example.com", ComposedEmail("subject", "body"), "welcome")

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `delivery failures are swallowed and never propagate`() {
        every { sender.send(any(), any(), any()) } throws EmailDeliveryException("provider down")

        // Must not throw — a broken provider can never fail the business transaction that requested it.
        delivery.deliver(UUID.randomUUID(), "alice@example.com", ComposedEmail("subject", "body"), "welcome")

        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `unexpected sender exceptions are swallowed too`() {
        every { sender.send(any(), any(), any()) } throws IllegalStateException("bug in a future sender")

        // The "never propagate" contract covers any exception, not just EmailDeliveryException.
        delivery.deliver(UUID.randomUUID(), "alice@example.com", ComposedEmail("subject", "body"), "payment-issue")

        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }
}
