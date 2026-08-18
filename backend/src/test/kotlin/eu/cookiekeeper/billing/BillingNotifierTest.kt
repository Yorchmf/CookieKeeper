package eu.cookiekeeper.billing

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.notify.BestEffortEmailDelivery
import eu.cookiekeeper.notify.ComposedEmail
import eu.cookiekeeper.notify.EmailSender
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

class BillingNotifierTest {
    private val composer = mockk<BillingEmailComposer>()
    private val sender = mockk<EmailSender>()
    private val userRepository = mockk<UserRepository>()

    private val trialPeriod: Duration = Duration.ofDays(14)

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            billing = CookieKeeperProperties.Billing(trialPeriod = trialPeriod),
            appBaseUrl = "https://app.cookiekeeper.eu",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val notifier =
        BillingNotifier(composer, BestEffortEmailDelivery(sender), userRepository, properties)

    private val userId: UUID = UUID.randomUUID()
    private val signupAt: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun user(locale: String = "de") =
        UserEntity(
            id = userId,
            email = "alice@example.com",
            passwordHash = "hash",
            locale = locale,
            createdAt = signupAt,
            verifiedAt = signupAt,
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

    /**
     * The end date is DERIVED at send time (`created_at + trial-period`) rather than carried on the
     * event, so it can never disagree with what [PlanResolver] shows the same user in the dashboard.
     */
    @Test
    fun `derives the trial end date from the signup date and the configured trial period`() {
        every { userRepository.findById(userId) } returns Optional.of(user(locale = "it"))
        every { composer.trialEndingEmail("it", signupAt.plus(trialPeriod)) } returns ComposedEmail("subject", "body")
        every { sender.send(any(), any(), any()) } just runs

        notifier.sendTrialEnding(userId)

        verify { sender.send("alice@example.com", "subject", "body") }
    }

    @Test
    fun `sends nothing when the user no longer exists`() {
        every { userRepository.findById(userId) } returns Optional.empty()

        notifier.sendSubscriptionActivated(userId, Plan.BUSINESS)
        notifier.sendPaymentIssue(userId)
        notifier.sendTrialEnding(userId)

        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }
}
