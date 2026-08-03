package com.complyr.notify

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthEmailListenerTest {
    private val notifier = mockk<AuthNotifier>()
    private val listener = AuthEmailListener(notifier)
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `verification events are dispatched to the notifier`() {
        every { notifier.sendVerification(userId, "alice@example.com", "de", "raw-token") } just runs

        listener.onAuthEmailRequested(VerificationEmailRequested(userId, "alice@example.com", "de", "raw-token"))

        verify(exactly = 1) { notifier.sendVerification(userId, "alice@example.com", "de", "raw-token") }
    }

    @Test
    fun `password reset events are dispatched to the notifier`() {
        every { notifier.sendPasswordReset(userId, "alice@example.com", "en", "raw-token") } just runs

        listener.onAuthEmailRequested(PasswordResetEmailRequested(userId, "alice@example.com", "en", "raw-token"))

        verify(exactly = 1) { notifier.sendPasswordReset(userId, "alice@example.com", "en", "raw-token") }
    }

    @Test
    fun `welcome events are dispatched to the notifier`() {
        every { notifier.sendWelcome(userId, "alice@example.com", "fr") } just runs

        listener.onAuthEmailRequested(WelcomeEmailRequested(userId, "alice@example.com", "fr"))

        verify(exactly = 1) { notifier.sendWelcome(userId, "alice@example.com", "fr") }
    }
}
