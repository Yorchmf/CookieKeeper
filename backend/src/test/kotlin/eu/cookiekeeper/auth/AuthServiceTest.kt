package eu.cookiekeeper.auth

import eu.cookiekeeper.auth.dto.LoginRequest
import eu.cookiekeeper.auth.dto.SignupRequest
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.notify.EmailChangedNoticeRequested
import eu.cookiekeeper.notify.PasswordResetEmailRequested
import eu.cookiekeeper.notify.VerificationEmailRequested
import eu.cookiekeeper.notify.WelcomeEmailRequested
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.sql.SQLException
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
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "support@cookiekeeper.eu",
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

    /** An account after the Art. 17 erasure rewrote it (ADR-20): synthetic address, `deletedAt` stamped. */
    private fun erasedUser(): UserEntity = user().copy(email = TOMBSTONE_EMAIL, deletedAt = now)

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

    /**
     * The Art. 17 tombstone's address is `erased-<user id>@erased.invalid` (ADR-20), and that user id is
     * printed in the account's own Art. 20 export — so anyone who ever held a copy of that file can derive
     * it. Issuing a reset link for it would hand them a working password on the shell of a deleted
     * account; the tombstone must look exactly like an address we have never seen.
     */
    @Test
    fun `forgotPassword for an erased account issues nothing`() {
        every { userRepository.findByEmail(TOMBSTONE_EMAIL) } returns erasedUser()

        service.forgotPassword(TOMBSTONE_EMAIL)

        verify(exactly = 0) { authTokenRepository.save(any()) }
        verify(exactly = 0) { authTokenRepository.markAllUsedByUserIdAndPurpose(any(), any(), any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `login as an erased account is rejected like an unknown email`() {
        every { userRepository.findByEmail(TOMBSTONE_EMAIL) } returns erasedUser()

        assertThrows<InvalidCredentialsException> {
            service.login(LoginRequest(email = TOMBSTONE_EMAIL, password = "correct-password"))
        }

        verify(exactly = 0) { tokenService.issueAccessToken(any(), any()) }
        // Not counted as a failure either: there is no account left to protect, and incrementing a
        // tombstone's counter would only write to a row the erasure just finished cleaning.
        verify(exactly = 0) { loginAttemptService.recordFailure(any()) }
    }

    @Test
    fun `resendVerification for an erased account sends nothing`() {
        every { userRepository.findByEmail(TOMBSTONE_EMAIL) } returns erasedUser()

        service.resendVerification(TOMBSTONE_EMAIL)

        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    /** The other half of the pair: a token minted moments before the erasure committed is still dead. */
    @Test
    fun `resetPassword refuses a token belonging to an erased account`() {
        val erased = erasedUser()
        val raw = OpaqueTokens.generate()
        every {
            authTokenRepository.findByTokenHashAndPurpose(OpaqueTokens.sha256(raw), TokenPurpose.PASSWORD_RESET)
        } returns
            AuthTokenEntity(
                userId = erased.id,
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = TokenPurpose.PASSWORD_RESET,
                expiresAt = now.plusSeconds(60),
            )
        every { authTokenRepository.save(any()) } answers { firstArg() }
        every { userRepository.findById(erased.id) } returns Optional.of(erased)

        assertThrows<InvalidTokenException> { service.resetPassword(raw, "brand-new-password") }

        verify(exactly = 0) { userRepository.save(any()) }
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

    @Test
    fun `confirmEmailChange swaps to the pending address, clears it, re-stamps verification and notifies the old address`() {
        stubSaves()
        val existing = user(verifiedAt = now.minusSeconds(3_600)).copy(pendingEmail = "new@example.com")
        val raw = OpaqueTokens.generate()
        val token = emailChangeToken(existing.id, raw)
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_CHANGE)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        val savedUser = slot<UserEntity>()

        val response = service.confirmEmailChange(raw)

        verify { userRepository.saveAndFlush(capture(savedUser)) }
        assertEquals("new@example.com", savedUser.captured.email, "the login email swaps to the parked address")
        assertEquals(null, savedUser.captured.pendingEmail, "the parked address is cleared once redeemed")
        // The click proves control of the address now bound to the account, so verification is (re-)stamped.
        assertEquals(now, savedUser.captured.verifiedAt)
        assertEquals("new@example.com", response.email)
        assertEquals(null, response.pendingEmail)
        verify { authTokenRepository.save(match { it.id == token.id && it.usedAt == now }) }

        val notice = publishedEvent<EmailChangedNoticeRequested>()
        // The heads-up goes to the OLD address, which just lost control of the account.
        assertEquals("alice@example.com", notice.email)
        assertEquals(existing.id, notice.userId)
    }

    @Test
    fun `confirmEmailChange with no parked address is rejected as a generic invalid token`() {
        val existing = user() // pendingEmail is null: a superseded change whose token should already be spent
        val raw = OpaqueTokens.generate()
        val token = emailChangeToken(existing.id, raw)
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_CHANGE)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        every { authTokenRepository.save(any()) } answers { firstArg() }

        assertThrows<InvalidTokenException> { service.confirmEmailChange(raw) }

        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `confirmEmailChange refuses a token belonging to an erased account`() {
        val erased = erasedUser().copy(pendingEmail = "new@example.com")
        val raw = OpaqueTokens.generate()
        val token = emailChangeToken(erased.id, raw)
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_CHANGE)
        } returns token
        every { userRepository.findById(erased.id) } returns Optional.of(erased)
        every { authTokenRepository.save(any()) } answers { firstArg() }

        assertThrows<InvalidTokenException> { service.confirmEmailChange(raw) }

        verify(exactly = 0) { userRepository.saveAndFlush(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `confirmEmailChange surfaces an address taken since the request as a 409, not a PII-leaking 500`() {
        val existing = user().copy(pendingEmail = "new@example.com")
        val raw = OpaqueTokens.generate()
        val token = emailChangeToken(existing.id, raw)
        every {
            authTokenRepository.findByTokenHashAndPurpose(token.tokenHash, TokenPurpose.EMAIL_CHANGE)
        } returns token
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        every { authTokenRepository.save(any()) } answers { firstArg() }
        // Another account registered the address in the window between request and confirmation: the unique
        // index is the authoritative check, and its breach must map to EMAIL_IN_USE.
        every { userRepository.saveAndFlush(any()) } throws integrityViolation("uq_users_email")

        assertThrows<EmailAlreadyRegisteredException> { service.confirmEmailChange(raw) }

        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    private fun emailChangeToken(
        userId: UUID,
        raw: String,
    ): AuthTokenEntity =
        AuthTokenEntity(
            userId = userId,
            tokenHash = OpaqueTokens.sha256(raw),
            purpose = TokenPurpose.EMAIL_CHANGE,
            expiresAt = now.plusSeconds(60),
        )

    private fun integrityViolation(constraintName: String): DataIntegrityViolationException =
        DataIntegrityViolationException(
            "dup",
            ConstraintViolationException("dup", SQLException("duplicate key"), constraintName),
        )

    /** Returns the single published event of type [T], failing if none was published. */
    private inline fun <reified T : Any> publishedEvent(): T {
        val published = mutableListOf<Any>()
        verify { eventPublisher.publishEvent(capture(published)) }
        val events = published.filterIsInstance<T>()
        assertEquals(1, events.size, "expected exactly one ${T::class.simpleName}, got: $published")
        return events.first()
    }

    private companion object {
        /** Shaped like the real tombstone address, which is derivable from the Art. 20 export. */
        const val TOMBSTONE_EMAIL = "erased-11111111-1111-1111-1111-111111111111@erased.invalid"
    }
}
