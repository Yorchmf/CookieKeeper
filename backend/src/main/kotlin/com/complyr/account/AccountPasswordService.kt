package com.complyr.account

import com.complyr.auth.LoginAttemptService
import com.complyr.auth.TokenService
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.common.ApiException
import com.complyr.common.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Everything the account does with its password column: confirm it (re-authentication), change it, and —
 * for the Art. 17 flow — destroy it. One component so the credential's brute-force controls and its
 * bcrypt-encoding live in a single place rather than being re-derived per caller.
 */
@Component
class AccountPasswordService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val loginAttemptService: LoginAttemptService,
    private val tokenService: TokenService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(AccountPasswordService::class.java)

    /**
     * Re-authentication for a sensitive action already inside an authenticated session, with the same
     * brute-force controls the login page has. Throws [onFailure] unless [password] is [user]'s current
     * password — the caller supplies the exception so each flow can name the field that was wrong (account
     * deletion, password change) rather than share one code.
     *
     * Re-authentication is the second place in the app where a password is verified, and it needs those
     * controls for the same reason login does: an attacker holding a stolen session (or sitting at an
     * unlocked machine) could otherwise guess the password against these endpoints with no counter, no
     * lock, and a distinct 403 telling them exactly when they hit. Reusing [LoginAttemptService] means a
     * spray here also locks the account against the login page and vice versa — one budget per account,
     * not one per endpoint.
     *
     * A locked account is refused before the hash comparison, which is also what stops the endpoint being
     * a bcrypt CPU-exhaustion primitive. Unlike login there is no anti-enumeration concern to balance (the
     * caller has already proved they hold this account's session), so the lock is not disguised as a wrong
     * password — but it is not counted as a fresh failure either, or a locked-out attacker could keep
     * extending their own lock.
     */
    fun confirm(
        user: UserEntity,
        password: String,
        onFailure: () -> ApiException,
    ) {
        if (user.lockedUntil?.isAfter(clock.instant()) == true) {
            log.warn("Rejected password re-authentication for {}: account is locked out", user.id)
            throw onFailure()
        }
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            // Commits in its own transaction, so the increment survives the exception below.
            loginAttemptService.recordFailure(user.id)
            log.warn("Rejected password re-authentication for {}: password confirmation failed", user.id)
            throw onFailure()
        }
        // A proven password clears any accumulated failure counter / lapsed lock, exactly as
        // AuthService.login does — otherwise someone who changed their password while near the threshold
        // would be locked out of the very next login with the CORRECT new password. Guarded like login's
        // call so a clean account never pays for the advisory lock or the write.
        if (user.failedLoginAttempts > 0 || user.lockedUntil != null) {
            loginAttemptService.clearFailures(user.id)
        }
    }

    /**
     * Rotates the account password from inside an authenticated session (dashboard "change password").
     *
     * [currentPassword] is re-authenticated through [confirm], so the same lockout applies. The new
     * password must differ from the current one — a no-op that still revoked every session would be a
     * footgun, and the check is here because it needs the stored hash. On success every refresh token is
     * revoked (mirroring [com.complyr.auth.AuthService.resetPassword]): a password change is exactly when
     * you want any other live session — including one an attacker may hold — invalidated, so the caller
     * clears this session's cookies too and the dashboard sends the user back to sign in.
     */
    @Transactional
    fun changePassword(
        userId: UUID,
        currentPassword: String,
        newPassword: String,
    ) {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        confirm(user, currentPassword) { CurrentPasswordIncorrectException() }
        if (passwordEncoder.matches(newPassword, user.passwordHash)) throw NewPasswordSameAsCurrentException()
        userRepository.save(user.copy(passwordHash = hash(newPassword)))
        tokenService.revokeAllForUser(user.id)
    }

    /**
     * A hash for the tombstone's `password_hash`: a real bcrypt hash of a value generated here and
     * immediately discarded. The column stays structurally valid for anything that reads it while being
     * impossible to match — safer than a sentinel string, which bcrypt would reject at parse time and
     * which would then surface as an error rather than a clean credential mismatch.
     */
    fun unmatchableHash(): String = hash(UUID.randomUUID().toString())

    private fun hash(rawPassword: String): String = requireNotNull(passwordEncoder.encode(rawPassword)) { "Password encoder returned null" }
}
