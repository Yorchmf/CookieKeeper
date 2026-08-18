package eu.cookiekeeper.account

import eu.cookiekeeper.auth.AuthTokenEntity
import eu.cookiekeeper.auth.AuthTokenRepository
import eu.cookiekeeper.auth.EmailAlreadyRegisteredException
import eu.cookiekeeper.auth.LoginAttemptService
import eu.cookiekeeper.auth.OpaqueTokens
import eu.cookiekeeper.auth.TokenPurpose
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.notify.EmailChangeRequested
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Unit tests for [AccountEmailService] — the "verify the new address first" request step (ADR-20).
 *
 * The security property under test is that the request only PARKS the new address and mails a link to it:
 * the account's login email is never touched here, the current password is re-authenticated first, and a
 * live-account collision is refused up front. The swap itself lives in
 * [eu.cookiekeeper.auth.AuthService.confirmEmailChange] and is covered in AuthServiceTest.
 */
class AccountEmailServiceTest {
    private val now: Instant = Instant.parse("2026-08-13T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                    emailChangeTokenTtl = EMAIL_CHANGE_TTL,
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )

    // Low bcrypt cost for test speed; production strength is configured in JwtConfig.
    private val passwordEncoder = BCryptPasswordEncoder(4)

    private val userRepository = mockk<UserRepository>()
    private val authTokenRepository = mockk<AuthTokenRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>()
    private val loginAttemptService = mockk<LoginAttemptService>(relaxed = true)

    private val service =
        AccountEmailService(
            userRepository = userRepository,
            authTokenRepository = authTokenRepository,
            passwordService =
                AccountPasswordService(
                    userRepository = userRepository,
                    passwordEncoder = passwordEncoder,
                    loginAttemptService = loginAttemptService,
                    tokenService = mockk(relaxed = true),
                    clock = clock,
                ),
            eventPublisher = eventPublisher,
            properties = properties,
            clock = clock,
        )

    private fun stubSaves() {
        every { userRepository.save(any()) } answers { firstArg() }
        every { authTokenRepository.save(any()) } answers { firstArg() }
        every { authTokenRepository.markAllUsedByUserIdAndPurpose(any(), any(), any()) } returns 0
        every { eventPublisher.publishEvent(any<Any>()) } just runs
    }

    private fun user(password: String = PASSWORD): UserEntity =
        UserEntity(
            id = USER_ID,
            email = CURRENT_EMAIL,
            passwordHash = requireNotNull(passwordEncoder.encode(password)),
            locale = "de",
            verifiedAt = now.minusSeconds(1_000),
        )

    private fun givenUser(user: UserEntity) {
        every { userRepository.findById(USER_ID) } returns Optional.of(user)
    }

    @Test
    fun `parks the new address and mails the confirmation link to it, leaving the login email untouched`() {
        stubSaves()
        givenUser(user())
        every { userRepository.findByEmail(NEW_EMAIL) } returns null
        val savedUser = slot<UserEntity>()
        val savedToken = slot<AuthTokenEntity>()

        val response = service.requestEmailChange(USER_ID, " New@Example.com ", PASSWORD)

        verify { userRepository.save(capture(savedUser)) }
        // The login email must NOT change here — only the pending address is parked.
        assertEquals(CURRENT_EMAIL, savedUser.captured.email, "the login email must stay untouched")
        assertEquals(NEW_EMAIL, savedUser.captured.pendingEmail, "the new address is normalized and parked")
        // The response reflects the parked state so the dashboard need not refetch `me`.
        assertEquals(CURRENT_EMAIL, response.email)
        assertEquals(NEW_EMAIL, response.pendingEmail)

        verify { authTokenRepository.save(capture(savedToken)) }
        assertEquals(TokenPurpose.EMAIL_CHANGE, savedToken.captured.purpose)
        assertEquals(now.plus(EMAIL_CHANGE_TTL), savedToken.captured.expiresAt)

        val event = publishedEvent<EmailChangeRequested>()
        // The link goes to the NEW address — redeeming it is what proves control of it.
        assertEquals(NEW_EMAIL, event.email)
        assertEquals(USER_ID, event.userId)
        assertEquals(OpaqueTokens.sha256(event.rawToken), savedToken.captured.tokenHash)
    }

    @Test
    fun `invalidates outstanding email-change tokens before parking the new address`() {
        stubSaves()
        givenUser(user())
        every { userRepository.findByEmail(NEW_EMAIL) } returns null

        service.requestEmailChange(USER_ID, NEW_EMAIL, PASSWORD)

        // Only the newest link may work: the retirement must precede the new token being minted.
        verifyOrder {
            authTokenRepository.markAllUsedByUserIdAndPurpose(USER_ID, TokenPurpose.EMAIL_CHANGE, now)
            authTokenRepository.save(any())
        }
    }

    @Test
    fun `rejects a wrong current password without parking anything or mailing a link`() {
        givenUser(user())

        assertThrows<CurrentPasswordIncorrectException> {
            service.requestEmailChange(USER_ID, NEW_EMAIL, "not-the-password")
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { authTokenRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
        // Re-authentication must spend the same lockout budget as the login page.
        verify { loginAttemptService.recordFailure(USER_ID) }
    }

    @Test
    fun `rejects a new email identical to the current one after normalization`() {
        givenUser(user())

        assertThrows<NewEmailSameAsCurrentException> {
            service.requestEmailChange(USER_ID, " Owner@Example.com ", PASSWORD)
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { authTokenRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `rejects a new email already held by a live account`() {
        givenUser(user())
        every { userRepository.findByEmail(NEW_EMAIL) } returns
            user().copy(id = UUID.randomUUID(), email = NEW_EMAIL)

        assertThrows<EmailAlreadyRegisteredException> {
            service.requestEmailChange(USER_ID, NEW_EMAIL, PASSWORD)
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `treats an erased tombstone at the new address as free and proceeds`() {
        stubSaves()
        givenUser(user())
        // A tombstone's derivable @erased.invalid address is not a real collision.
        every { userRepository.findByEmail(NEW_EMAIL) } returns
            user().copy(id = UUID.randomUUID(), email = NEW_EMAIL, deletedAt = now.minusSeconds(500))

        val response = service.requestEmailChange(USER_ID, NEW_EMAIL, PASSWORD)

        assertEquals(NEW_EMAIL, response.pendingEmail)
        verify { userRepository.save(any()) }
        publishedEvent<EmailChangeRequested>()
    }

    @Test
    fun `a missing account is unauthenticated`() {
        every { userRepository.findById(USER_ID) } returns Optional.empty()

        assertThrows<UnauthenticatedException> {
            service.requestEmailChange(USER_ID, NEW_EMAIL, PASSWORD)
        }
    }

    /** Returns the single published event of type [T], failing if none was published. */
    private inline fun <reified T : Any> publishedEvent(): T {
        val published = mutableListOf<Any>()
        verify { eventPublisher.publishEvent(capture(published)) }
        val events = published.filterIsInstance<T>()
        assertEquals(1, events.size, "expected exactly one ${T::class.simpleName}, got: $published")
        return events.first()
    }

    private companion object {
        val EMAIL_CHANGE_TTL: Duration = Duration.ofHours(24)
        val USER_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        const val CURRENT_EMAIL = "owner@example.com"
        const val NEW_EMAIL = "new@example.com"
        const val PASSWORD = "correct horse battery staple"
    }
}
