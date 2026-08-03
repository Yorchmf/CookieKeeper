package com.complyr.auth

import com.complyr.common.ComplyrProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Maintains the per-account failed-login counter and temporary lock window that back [AuthService]'s
 * distributed-brute-force guard (V14). Split out from [AuthService] for one reason that matters:
 * [recordFailure] runs in its OWN transaction ([Propagation.REQUIRES_NEW]) so the incremented counter
 * COMMITS even though the caller ([AuthService.login]) immediately throws [InvalidCredentialsException]
 * and rolls its own transaction back. A method on `AuthService` itself could not do this — Spring's
 * proxy ignores self-invocation, so the new-transaction boundary has to live on a separate bean.
 */
@Service
class LoginAttemptService(
    private val userRepository: UserRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    /**
     * Records one failed login for [userId], locking the account once it reaches
     * [ComplyrProperties.Auth.maxFailedLoginAttempts] consecutive failures. Serialized per-account by a
     * transaction-scoped advisory lock so concurrent guesses can't lose an update and slip extra
     * attempts past the cap. A no-op if the user vanished (deleted between login's read and this call).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFailure(userId: UUID) {
        userRepository.acquireUserLoginLock(advisoryLockKey(userId))
        val user = userRepository.findById(userId).orElse(null) ?: return
        val now = clock.instant()
        val lockedUntil = user.lockedUntil
        // Already actively locked: leave the window untouched. AuthService.login() only calls us after a
        // pre-lock snapshot said the account was unlocked, but under concurrent guessing (the botnet this
        // guards against) that snapshot is stale — a straggler can reach here just after another attempt
        // set the lock. Bumping the (reset-to-0) counter would compute newFailures=1, decide "not locked",
        // and clear lockedUntil — silently UNLOCKING the account and defeating the cap. Do nothing instead.
        if (lockedUntil != null && lockedUntil.isAfter(now)) return
        // Past this point the lock is either absent or lapsed. A lapsed window means the prior count is
        // stale, so this failure starts a fresh window; otherwise continue the running count.
        val priorFailures = if (lockedUntil != null) 0 else user.failedLoginAttempts
        val newFailures = priorFailures + 1
        val locked = newFailures >= properties.auth.maxFailedLoginAttempts
        userRepository.save(
            user.copy(
                // Reset the counter on lock so the window (not a lingering count) is what gates the next
                // attempts: after it lapses the account gets a fresh full budget, not instant re-lock.
                failedLoginAttempts = if (locked) 0 else newFailures,
                lockedUntil = if (locked) now.plus(properties.auth.loginLockoutDuration) else null,
            ),
        )
    }

    /**
     * Clears a non-empty failure counter / lock after a successful login. Own transaction so it is
     * independent of the caller, and takes the SAME per-account advisory lock as [recordFailure]: a
     * legitimate user logging in correctly (this path) and an attacker guessing wrong ([recordFailure])
     * are two different actors that genuinely run concurrently against one account during an attack, so
     * serializing them keeps this read-modify-write from losing an update against a concurrent bump.
     * [AuthService] only calls this when the loaded user already shows a non-zero counter / active lock,
     * so the common (clean) login never pays for the lock or the write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun clearFailures(userId: UUID) {
        userRepository.acquireUserLoginLock(advisoryLockKey(userId))
        val user = userRepository.findById(userId).orElse(null) ?: return
        if (user.failedLoginAttempts == 0 && user.lockedUntil == null) return
        userRepository.save(user.copy(failedLoginAttempts = 0, lockedUntil = null))
    }

    // Fold the 128-bit user id into the 64-bit key pg_advisory_xact_lock takes (mirrors EntitlementService),
    // XOR-salted to sit in a distinct key space from that per-user site-cap lock so a login-failure bump
    // never needlessly serializes against the same user's site-create guard. A rare cross-user collision
    // only causes harmless extra serialization, never a miss.
    private fun advisoryLockKey(userId: UUID): Long = (userId.mostSignificantBits xor userId.leastSignificantBits) xor LOGIN_LOCK_SALT

    companion object {
        // Arbitrary fixed salt namespacing the login-lockout advisory-lock key space; kept distinct from
        // every other `pg_advisory*` key the app takes.
        private const val LOGIN_LOCK_SALT: Long = 0x4C4F_4749_4E5F_314CL
    }
}
