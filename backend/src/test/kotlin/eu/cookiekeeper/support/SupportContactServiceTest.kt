package eu.cookiekeeper.support

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.notify.EmailDeliveryException
import eu.cookiekeeper.notify.EmailSender
import eu.cookiekeeper.support.dto.SupportContactRequest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SupportContactService]. The properties under test: the message goes to OUR support
 * inbox with the submitting customer's own address as Reply-To (never in the To), the subject/body are
 * composed from the request, and a mail-provider failure surfaces as [ContactDeliveryFailedException]
 * rather than being swallowed — a support message the sender thinks was delivered but was not is the
 * failure mode we refuse.
 */
class SupportContactServiceTest {
    private val userRepository = mockk<UserRepository>()
    private val emailSender = mockk<EmailSender>()
    private val properties = props(supportInbox = "support@complyr.eu")
    private val service = SupportContactService(userRepository, emailSender, ContactEmailComposer(), properties)

    private val userId = UUID.randomUUID()

    @Test
    fun `sends to the support inbox with the customer as reply-to`() {
        givenUser(email = "alice@example.com", locale = "de")
        val to = slot<String>()
        val replyTo = slot<String?>()
        val body = slot<String>()
        every { emailSender.send(capture(to), any(), capture(body), captureNullable(replyTo)) } just runs

        service.submit(userId, SupportContactRequest(subject = "Billing question", message = "How do I upgrade?"))

        assertEquals("support@complyr.eu", to.captured)
        assertEquals("alice@example.com", replyTo.captured)
        assertTrue(body.captured.contains("Billing question"))
        assertTrue(body.captured.contains("How do I upgrade?"))
        assertTrue(body.captured.contains("alice@example.com"))
    }

    @Test
    fun `translates a delivery failure into a 503 without swallowing it`() {
        givenUser()
        every { emailSender.send(any(), any(), any(), any()) } throws EmailDeliveryException("provider down")

        assertThrows<ContactDeliveryFailedException> {
            service.submit(userId, SupportContactRequest(subject = "S", message = "M"))
        }
    }

    @Test
    fun `rejects a token whose account no longer exists and never sends`() {
        every { userRepository.findById(userId) } returns Optional.empty()

        assertThrows<UnauthenticatedException> {
            service.submit(userId, SupportContactRequest(subject = "S", message = "M"))
        }
        verify(exactly = 0) { emailSender.send(any(), any(), any(), any()) }
    }

    private fun givenUser(
        email: String = "user@example.com",
        locale: String = "en",
    ) {
        val user = UserEntity(id = userId, email = email, passwordHash = "x", locale = locale)
        every { userRepository.findById(userId) } returns Optional.of(user)
    }

    private fun props(supportInbox: String): CookieKeeperProperties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
            supportInbox = supportInbox,
        )
}
