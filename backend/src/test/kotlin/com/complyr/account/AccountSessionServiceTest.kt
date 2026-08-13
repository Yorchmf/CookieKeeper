package com.complyr.account

import com.complyr.auth.LoginAttemptService
import com.complyr.auth.TokenService
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.common.UnauthenticatedException
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [AccountSessionService] — "sign out of all devices".
 *
 * The property under test: the operation re-authenticates with the current password (spending the shared
 * login-lockout budget on a miss) and only then revokes every refresh token. A wrong password must revoke
 * nothing, and a missing account must be treated as unauthenticated rather than half-run.
 */
class AccountSessionServiceTest {
    private val now: Instant = Instant.parse("2026-08-13T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    // Low bcrypt cost for test speed; production strength is configured in JwtConfig.
    private val passwordEncoder = BCryptPasswordEncoder(4)

    private val userRepository = mockk<UserRepository>()
    private val loginAttemptService = mockk<LoginAttemptService>(relaxed = true)
    private val tokenService = mockk<TokenService>()

    private val service =
        AccountSessionService(
            userRepository = userRepository,
            passwordService =
                AccountPasswordService(
                    userRepository = userRepository,
                    passwordEncoder = passwordEncoder,
                    loginAttemptService = loginAttemptService,
                    tokenService = tokenService,
                    clock = clock,
                ),
            tokenService = tokenService,
        )

    private fun user(password: String = PASSWORD): UserEntity =
        UserEntity(
            id = USER_ID,
            email = EMAIL,
            passwordHash = requireNotNull(passwordEncoder.encode(password)),
            locale = "de",
            verifiedAt = now.minusSeconds(1_000),
        )

    private fun givenUser(user: UserEntity) {
        every { userRepository.findById(USER_ID) } returns Optional.of(user)
    }

    @Test
    fun `revokes every refresh token after the current password is confirmed`() {
        givenUser(user())
        every { tokenService.revokeAllForUser(USER_ID) } just runs

        service.revokeAllSessions(USER_ID, PASSWORD)

        verify { tokenService.revokeAllForUser(USER_ID) }
        // A correct password must not spend the lockout budget.
        verify(exactly = 0) { loginAttemptService.recordFailure(USER_ID) }
    }

    @Test
    fun `rejects a wrong current password without revoking anything`() {
        givenUser(user())

        assertThrows<CurrentPasswordIncorrectException> {
            service.revokeAllSessions(USER_ID, "not-the-password")
        }

        verify(exactly = 0) { tokenService.revokeAllForUser(any()) }
        // Re-authentication must spend the same lockout budget as the login page.
        verify { loginAttemptService.recordFailure(USER_ID) }
    }

    @Test
    fun `refuses a locked-out account without revoking or spending a fresh failure`() {
        givenUser(user().copy(lockedUntil = now.plusSeconds(300)))

        assertThrows<CurrentPasswordIncorrectException> {
            service.revokeAllSessions(USER_ID, PASSWORD)
        }

        verify(exactly = 0) { tokenService.revokeAllForUser(any()) }
        // A lockout is refused before the hash comparison and must not extend its own lock.
        verify(exactly = 0) { loginAttemptService.recordFailure(any()) }
    }

    @Test
    fun `a missing account is unauthenticated and revokes nothing`() {
        every { userRepository.findById(USER_ID) } returns Optional.empty()

        assertThrows<UnauthenticatedException> {
            service.revokeAllSessions(USER_ID, PASSWORD)
        }

        verify(exactly = 0) { tokenService.revokeAllForUser(any()) }
    }

    private companion object {
        val USER_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        const val EMAIL = "owner@example.com"
        const val PASSWORD = "correct horse battery staple"
    }
}
