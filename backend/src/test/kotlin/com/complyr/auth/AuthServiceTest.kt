package com.complyr.auth

import com.complyr.auth.dto.LoginRequest
import com.complyr.auth.dto.SignupRequest
import com.complyr.common.ComplyrProperties
import com.complyr.notify.PasswordResetEmailRequested
import com.complyr.notify.VerificationEmailRequested
import com.complyr.notify.WelcomeEmailRequested
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
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )

    private val now: Instant = Instant.parse("2026-07-28T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    // Low bcrypt cost for test speed; production strength is configured in JwtConfig.
    private val passwordEncoder = BCryptPasswordEncoder(4)

    private val userRepository = mockk<UserRepository>()
    private val authTokenRepository = mockk<AuthTokenRepository>()
    private val tokenService = mockk<TokenService>()
    private val eventPublisher = mockk<ApplicationEventPublisher>()
    private val loginAttemptService = mockk<LoginAttemptService>(relaxed = true)

    private val service =
        AuthService(
            userRepository,
            authTokenRepository,
            tokenService,
            passwordEncoder,
            eventPublisher,
            loginAttemptService,
            properties,
            clock,
        )

    private fun stubSaves() {
        every { userRepository.save(any()) } answers { firstArg() }
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }
        every { authTokenRepository.save(any()) } answers { firstArg() }
        every { eventPublisher.publishEvent(any<Any>()) } just runs
    }

    private fun user(
        password: String = "correct-password",
        verifiedAt: Instant? = null,
    ): UserEntity =
        UserEntity(
            email = "alice@example.com",
            passwordHash = requireNotNull(passwordEncoder.encode(password)),
            locale = "de",
            verifiedAt = verifiedAt,
        )

    @Test
    fun `signup stores a bcrypt hash, never the plaintext password`() {
        stubSaves()
        every { userRepository.findByEmail("alice@example.com") } returns null
        val savedUser = slot<UserEntity>()

        service.signup(SignupRequest(email = " Alice@Example.com ", password = "s3cret-password", locale = "de"))

        verify { userRepository.saveAndFlush(capture(savedUser)) }
        assertEquals("alice@example.com", savedUser.captured.email, "email must be normalized")
        assertNotEquals("s3cret-password", savedUser.captured.passwordHash)
        assertTrue(passwordEncoder.matches("s3cret-password", savedUser.captured.passwordHash))
    }

    @Test
    fun `signup publishes a verification email event whose token is stored hashed`() {
        stubSaves()
        every { userRepository.findByEmail(any()) } returns null
        val savedToken = slot<AuthTokenEntity>()

        service.signup(SignupRequest(email = "alice@example.com", password = "s3cret-password", locale = "de"))

        verify { authTokenRepository.save(capture(savedToken)) }
        val event = publishedEvent<VerificationEmailRequested>()
        assertEquals("alice@example.com", event.email)
        assertEquals("de", event.locale)
        assertEquals(TokenPurpose.EMAIL_VERIFICATION, savedToken.captured.purpose)
        assertEquals(OpaqueTokens.sha256(event.rawToken), savedToken.captured.tokenHash)
        assertEquals(now.plus(Duration.ofHours(24)), savedToken.captured.expiresAt)
    }

    @Test
    fun `signup with an already registered email is rejected`() {
        every { userRepository.findByEmail("alice@example.com") } returns user()

        assertThrows<EmailAlreadyRegisteredException> {
            service.signup(SignupRequest(email = "alice@example.com", password = "s3cret-password", locale = "en"))
        }

        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
    }

    @Test
    fun `signup returns the created user even when an email event is published`() {
        stubSaves()
        every { userRepository.findByEmail(any()) } returns null

        val response =
            service.signup(SignupRequest(email = "alice@example.com", password = "s3cret-password", locale = "en"))

        assertEquals("alice@example.com", response.email)
        assertEquals("en", response.locale)
    }

    @Test
    fun `wrong password and unknown email produce the identical exception`() {
        every { userRepository.findByEmail("alice@example.com") } returns user(password = "correct-password")
        every { userRepository.findByEmail("ghost@example.com") } returns null

        val wrongPassword =
            assertThrows<InvalidCredentialsException> {
                service.login(LoginRequest(email = "alice@example.com", password = "wrong-password"))
            }
        val unknownEmail =
            assertThrows<InvalidCredentialsException> {
                service.login(LoginRequest(email = "ghost@example.com", password = "whatever-pass"))
            }

        assertEquals(wrongPassword.message, unknownEmail.message)
        assertEquals(wrongPassword.code, unknownEmail.code)
    }

    @Test
    fun `login issues an access and refresh token pair`() {
        val existing = user(password = "correct-password", verifiedAt = now)
        every { userRepository.findByEmail("alice@example.com") } returns existing
        every { tokenService.issueAccessToken(existing.id, emailVerified = true) } returns "access-jwt"
        every { tokenService.issueRefreshToken(existing.id) } returns
            IssuedRefreshToken(existing.id, "raw-refresh")

        val session = service.login(LoginRequest(email = "alice@example.com", password = "correct-password"))

        assertEquals("access-jwt", session.accessToken)
        assertEquals("raw-refresh", session.refreshToken)
        assertEquals(existing.id, session.user.id)
    }

    @Test
    fun `a wrong password records a failed login attempt`() {
        val existing = user(password = "correct-password")
        every { userRepository.findByEmail("alice@example.com") } returns existing

        assertThrows<InvalidCredentialsException> {
            service.login(LoginRequest(email = "alice@example.com", password = "wrong-password"))
        }

        verify(exactly = 1) { loginAttemptService.recordFailure(existing.id) }
    }

    @Test
    fun `a locked account is rejected even with the correct password and records no new attempt`() {
        val locked =
            user(password = "correct-password").copy(lockedUntil = now.plusSeconds(600))
        every { userRepository.findByEmail("alice@example.com") } returns locked

        assertThrows<InvalidCredentialsException> {
            service.login(LoginRequest(email = "alice@example.com", password = "correct-password"))
        }

        // The lock short-circuits the real password check, so a locked account never issues a session…
        verify(exactly = 0) { tokenService.issueRefreshToken(any()) }
        // …and the lock rejection is not itself a "failed attempt" (it would never let the window lapse).
        verify(exactly = 0) { loginAttemptService.recordFailure(any()) }
    }

    @Test
    fun `an elapsed lock no longer blocks a valid login and clears the stale counter`() {
        val elapsed =
            user(password = "correct-password", verifiedAt = now)
                .copy(failedLoginAttempts = 0, lockedUntil = now.minusSeconds(1))
        every { userRepository.findByEmail("alice@example.com") } returns elapsed
        every { tokenService.issueAccessToken(elapsed.id, emailVerified = true) } returns "access-jwt"
        every { tokenService.issueRefreshToken(elapsed.id) } returns IssuedRefreshToken(elapsed.id, "raw-refresh")

        val session = service.login(LoginRequest(email = "alice@example.com", password = "correct-password"))

        assertEquals("access-jwt", session.accessToken)
        verify(exactly = 1) { loginAttemptService.clearFailures(elapsed.id) }
    }

    @Test
    fun `a clean successful login never touches the attempt tracker`() {
        val existing = user(password = "correct-password", verifiedAt = now)
        every { userRepository.findByEmail("alice@example.com") } returns existing
        every { tokenService.issueAccessToken(existing.id, emailVerified = true) } returns "access-jwt"
        every { tokenService.issueRefreshToken(existing.id) } returns IssuedRefreshToken(existing.id, "raw-refresh")

        service.login(LoginRequest(email = "alice@example.com", password = "correct-password"))

        verify(exactly = 0) { loginAttemptService.clearFailures(any()) }
        verify(exactly = 0) { loginAttemptService.recordFailure(any()) }
    }

    @Test
    fun `a successful login clears accumulated failures`() {
        val existing =
            user(password = "correct-password", verifiedAt = now).copy(failedLoginAttempts = 3)
        every { userRepository.findByEmail("alice@example.com") } returns existing
        every { tokenService.issueAccessToken(existing.id, emailVerified = true) } returns "access-jwt"
        every { tokenService.issueRefreshToken(existing.id) } returns IssuedRefreshToken(existing.id, "raw-refresh")

        service.login(LoginRequest(email = "alice@example.com", password = "correct-password"))

        verify(exactly = 1) { loginAttemptService.clearFailures(existing.id) }
    }

    @Test
    fun `verifyEmail marks the token used and the user verified`() {
        stubSaves()
        val existing = user()
        val raw = OpaqueTokens.generate()
        val token =
            AuthTokenEntity(
                userId = existing.id,
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = now.plusSeconds(60),
            )
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_VERIFICATION)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        val response = service.verifyEmail(raw)

        assertNotNull(response.verifiedAt)
        verify { authTokenRepository.save(match { it.id == token.id && it.usedAt == now }) }
        verify { userRepository.save(match { it.id == existing.id && it.verifiedAt == now }) }
        val welcome = publishedEvent<WelcomeEmailRequested>()
        assertEquals(existing.id, welcome.userId)
        assertEquals("alice@example.com", welcome.email)
        assertEquals("de", welcome.locale)
    }

    @Test
    fun `re-verifying an already-verified account does not re-send the welcome email`() {
        stubSaves()
        val existing = user(verifiedAt = now.minusSeconds(3600))
        val raw = OpaqueTokens.generate()
        val token =
            AuthTokenEntity(
                userId = existing.id,
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = now.plusSeconds(60),
            )
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_VERIFICATION)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        service.verifyEmail(raw)

        verify(exactly = 0) { eventPublisher.publishEvent(any<WelcomeEmailRequested>()) }
    }

    @Test
    fun `expired verification token is rejected without being consumed`() {
        val raw = OpaqueTokens.generate()
        val token =
            AuthTokenEntity(
                userId = UUID.randomUUID(),
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = now.minusSeconds(1),
            )
        every { authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, any()) } returns token

        assertThrows<InvalidTokenException> { service.verifyEmail(raw) }

        verify(exactly = 0) { authTokenRepository.save(any()) }
    }

    @Test
    fun `already-used verification token is rejected`() {
        val raw = OpaqueTokens.generate()
        val token =
            AuthTokenEntity(
                userId = UUID.randomUUID(),
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.EMAIL_VERIFICATION,
                expiresAt = now.plusSeconds(60),
                usedAt = now.minusSeconds(30),
            )
        every { authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, any()) } returns token

        assertThrows<InvalidTokenException> { service.verifyEmail(raw) }
    }

    @Test
    fun `unknown verification token is rejected with the same generic error`() {
        every { authTokenRepository.findByTokenHashAndPurpose(any(), any()) } returns null

        assertThrows<InvalidTokenException> { service.verifyEmail("unknown-token") }
    }

    @Test
    fun `forgotPassword for an unknown email is a silent no-op`() {
        every { userRepository.findByEmail("ghost@example.com") } returns null

        service.forgotPassword("ghost@example.com")

        verify(exactly = 0) { authTokenRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `forgotPassword invalidates outstanding reset tokens before issuing a new one`() {
        stubSaves()
        val existing = user()
        every { userRepository.findByEmail("alice@example.com") } returns existing
        every {
            authTokenRepository.markAllUsedByUserIdAndPurpose(existing.id, TokenPurpose.PASSWORD_RESET, now)
        } returns 2
        val savedToken = slot<AuthTokenEntity>()

        service.forgotPassword("alice@example.com")

        verifyOrder {
            authTokenRepository.markAllUsedByUserIdAndPurpose(existing.id, TokenPurpose.PASSWORD_RESET, now)
            authTokenRepository.save(capture(savedToken))
        }
        assertEquals(TokenPurpose.PASSWORD_RESET, savedToken.captured.purpose)
        val event = publishedEvent<PasswordResetEmailRequested>()
        assertEquals(OpaqueTokens.sha256(event.rawToken), savedToken.captured.tokenHash)
    }

    @Test
    fun `resendVerification for an already verified user sends nothing`() {
        every { userRepository.findByEmail("alice@example.com") } returns user(verifiedAt = now)

        service.resendVerification("alice@example.com")

        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `login for an unknown email still burns one password match (timing equalizer)`() {
        val encoder = mockk<PasswordEncoder>()
        every { encoder.encode(any()) } returns "encoded-equalizer"
        every { encoder.matches(any(), any()) } returns false
        val timingService =
            AuthService(
                userRepository,
                authTokenRepository,
                tokenService,
                encoder,
                eventPublisher,
                loginAttemptService,
                properties,
                clock,
            )
        every { userRepository.findByEmail("ghost@example.com") } returns null

        assertThrows<InvalidCredentialsException> {
            timingService.login(LoginRequest(email = "ghost@example.com", password = "whatever-pass"))
        }

        verify(exactly = 1) { encoder.matches("whatever-pass", "encoded-equalizer") }
    }

    @Test
    fun `resetPassword updates the hash and revokes every refresh token`() {
        stubSaves()
        val existing = user(password = "old-password!")
        val raw = OpaqueTokens.generate()
        val token =
            AuthTokenEntity(
                userId = existing.id,
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.PASSWORD_RESET,
                expiresAt = now.plusSeconds(60),
            )
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.PASSWORD_RESET)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        every { tokenService.revokeAllForUser(existing.id) } just runs
        val savedUser = slot<UserEntity>()

        service.resetPassword(raw, "brand-new-password")

        verify { userRepository.save(capture(savedUser)) }
        assertTrue(passwordEncoder.matches("brand-new-password", savedUser.captured.passwordHash))
        verify(exactly = 1) { tokenService.revokeAllForUser(existing.id) }
    }

    /** Returns the single published event of type [T], failing if none was published. */
    private inline fun <reified T : Any> publishedEvent(): T {
        val published = mutableListOf<Any>()
        verify { eventPublisher.publishEvent(capture(published)) }
        val events = published.filterIsInstance<T>()
        assertEquals(1, events.size, "expected exactly one ${T::class.simpleName}, got: $published")
        return events.first()
    }
}
