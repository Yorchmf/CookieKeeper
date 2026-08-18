package eu.cookiekeeper.notify

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthNotifierTest {
    private val composer = mockk<AuthEmailComposer>()
    private val sender = mockk<EmailSender>()
    private val notifier = AuthNotifier(composer, BestEffortEmailDelivery(sender))

    @Test
    fun `delivers the composed verification email`() {
        every { composer.verificationEmail("de", "raw-token") } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs

        notifier.sendVerification(UUID.randomUUID(), "alice@example.com", "de", "raw-token")

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `delivers the composed welcome email`() {
        every { composer.welcomeEmail("en") } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs

        notifier.sendWelcome(UUID.randomUUID(), "alice@example.com", "en")

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `email delivery failures are swallowed and never propagate`() {
        every { composer.passwordResetEmail(any(), any()) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } throws EmailDeliveryException("smtp down")

        // Must not throw — a broken relay can never fail signup/reset transactions.
        notifier.sendPasswordReset(UUID.randomUUID(), "alice@example.com", "en", "raw-token")

        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `unexpected sender exceptions are swallowed too`() {
        every { composer.verificationEmail(any(), any()) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } throws IllegalStateException("bug in a future sender")

        // The "never propagate" contract covers any exception, not just EmailDeliveryException.
        notifier.sendVerification(UUID.randomUUID(), "alice@example.com", "en", "raw-token")

        verify(exactly = 1) { sender.send(any(), any(), any()) }
    }
}
