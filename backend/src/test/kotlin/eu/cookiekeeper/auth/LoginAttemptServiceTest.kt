package eu.cookiekeeper.auth

import eu.cookiekeeper.common.CookieKeeperProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginAttemptServiceTest {
    private val now: Instant = Instant.parse("2026-07-28T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val lockoutDuration: Duration = Duration.ofMinutes(15)

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                    maxFailedLoginAttempts = 3,
                    loginLockoutDuration = lockoutDuration,
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "http://localhost:8081",
            mailFrom = "no-reply@complyr.eu",
        )

    private val userRepository = mockk<UserRepository>()
    private val service = LoginAttemptService(userRepository, properties, clock)

    init {
        every { userRepository.acquireUserLoginLock(any()) } returns 1L
        every { userRepository.save(any()) } answers { firstArg() }
    }

    private fun user(
        failedLoginAttempts: Int = 0,
        lockedUntil: Instant? = null,
    ): UserEntity =
        UserEntity(
            email = "alice@example.com",
            passwordHash = requireNotNull(BCryptPasswordEncoder(4).encode("pw")),
            failedLoginAttempts = failedLoginAttempts,
            lockedUntil = lockedUntil,
        )

    @Test
    fun `a failure below the threshold increments the counter without locking`() {
        val existing = user(failedLoginAttempts = 1)
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        val saved = slot<UserEntity>()

        service.recordFailure(existing.id)

        verify { userRepository.save(capture(saved)) }
        assertEquals(2, saved.captured.failedLoginAttempts)
        assertNull(saved.captured.lockedUntil)
    }

    @Test
    fun `the threshold failure locks the account and resets the counter`() {
        val existing = user(failedLoginAttempts = 2) // threshold is 3
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        val saved = slot<UserEntity>()

        service.recordFailure(existing.id)

        verify { userRepository.save(capture(saved)) }
        assertEquals(0, saved.captured.failedLoginAttempts, "counter resets so the window gates re-tries")
        assertEquals(now.plus(lockoutDuration), saved.captured.lockedUntil)
    }

    @Test
    fun `a failure after the lock window elapsed starts a fresh count of one`() {
        val existing = user(failedLoginAttempts = 0, lockedUntil = now.minusSeconds(1))
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        val saved = slot<UserEntity>()

        service.recordFailure(existing.id)

        verify { userRepository.save(capture(saved)) }
        assertEquals(1, saved.captured.failedLoginAttempts)
        assertNull(saved.captured.lockedUntil, "the elapsed lock is cleared, not re-applied")
    }

    @Test
    fun `recordFailure leaves an already-active lock untouched so a concurrent straggler cannot unlock it`() {
        // Regression for the concurrency bug both reviewers flagged: a failed attempt that read the
        // pre-lock snapshot in AuthService.login() can still reach recordFailure just after another
        // attempt set the lock. It must NOT reset the counter or clear lockedUntil (which would silently
        // unlock the account and make the whole lockout a no-op against a concurrent botnet).
        val existing = user(failedLoginAttempts = 0, lockedUntil = now.plusSeconds(600))
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        service.recordFailure(existing.id)

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `recordFailure takes the per-account advisory lock before reading`() {
        val existing = user(failedLoginAttempts = 0)
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        service.recordFailure(existing.id)

        verify { userRepository.acquireUserLoginLock(any()) }
    }

    @Test
    fun `recordFailure is a no-op when the user has vanished`() {
        val id = UUID.randomUUID()
        every { userRepository.findById(id) } returns Optional.empty()

        service.recordFailure(id)

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `clearFailures resets a non-empty counter and lock`() {
        val existing = user(failedLoginAttempts = 2, lockedUntil = now.plusSeconds(600))
        every { userRepository.findById(existing.id) } returns Optional.of(existing)
        val saved = slot<UserEntity>()

        service.clearFailures(existing.id)

        verify { userRepository.save(capture(saved)) }
        assertEquals(0, saved.captured.failedLoginAttempts)
        assertNull(saved.captured.lockedUntil)
    }

    @Test
    fun `clearFailures takes the per-account advisory lock before reading`() {
        val existing = user(failedLoginAttempts = 2, lockedUntil = now.plusSeconds(600))
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        service.clearFailures(existing.id)

        verify { userRepository.acquireUserLoginLock(any()) }
    }

    @Test
    fun `clearFailures is a no-op when the counter is already clean`() {
        val existing = user(failedLoginAttempts = 0, lockedUntil = null)
        every { userRepository.findById(existing.id) } returns Optional.of(existing)

        service.clearFailures(existing.id)

        verify(exactly = 0) { userRepository.save(any()) }
    }
}
