package com.complyr.auth

import com.complyr.auth.dto.LoginRequest
import com.complyr.auth.dto.SignupRequest
import com.complyr.auth.dto.UserResponse
import com.complyr.common.ComplyrProperties
import com.complyr.common.UnauthenticatedException
import com.complyr.common.violatedConstraint
import com.complyr.notify.EmailChangedNoticeRequested
import com.complyr.notify.PasswordResetEmailRequested
import com.complyr.notify.VerificationEmailRequested
import com.complyr.notify.WelcomeEmailRequested
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Access + refresh pair issued at login/refresh; the controller turns these into cookies. */
data class AuthSession(
    val user: UserResponse,
    val accessToken: String,
    val refreshToken: String,
)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val authTokenRepository: AuthTokenRepository,
    private val tokenService: TokenService,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
    private val loginAttemptService: LoginAttemptService,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    // Matched against on login for unknown emails so both branches cost one bcrypt
    // verification — otherwise the fast unknown-email path is a timing oracle.
    private val timingEqualizerHash: String = hashPassword(TIMING_EQUALIZER_PASSWORD)

    @Transactional
    fun signup(request: SignupRequest): UserResponse {
        val email = normalizeEmail(request.email)
        // Deliberate MVP trade-off: a 409 EMAIL_IN_USE response enables account enumeration
        // on signup. Accepted for launch velocity — revisit before public launch.
        if (userRepository.findByEmail(email) != null) throw EmailAlreadyRegisteredException()
        val user =
            saveEnsuringEmailUniqueness(
                UserEntity(
                    email = email,
                    passwordHash = hashPassword(request.password),
                    locale = request.locale,
                    createdAt = clock.instant(),
                ),
            )
        sendVerificationEmail(user)
        return UserResponse.from(user)
    }

    /**
     * Persists (with flush) so a concurrent signup racing past the [findByEmail][UserRepository]
     * check is decided by the `uq_users_email` unique index and surfaces as a 409 — rather than an
     * uncaught [DataIntegrityViolationException] whose Postgres detail message would leak the email
     * address (PII) into logs and return a 500. Any other integrity violation is rethrown.
     */
    private fun saveEnsuringEmailUniqueness(user: UserEntity): UserEntity =
        try {
            userRepository.saveAndFlush(user)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.violatedConstraint() == UNIQUE_EMAIL_CONSTRAINT) throw EmailAlreadyRegisteredException()
            throw ex
        }

    @Transactional
    fun login(request: LoginRequest): AuthSession {
        val user = findLiveAccountByEmail(request.email)
        // Unknown email OR a locked account (distributed brute-force backstop, see LoginAttemptService):
        // burn one bcrypt (anti-enumeration timing) and reject with the SAME generic error as a wrong
        // password. A locked account must be indistinguishable from a wrong password / unknown email —
        // revealing "account locked" would leak that the email exists and hand an attacker a live oracle
        // for whether their spray is landing.
        if (user == null || user.lockedUntil?.isAfter(clock.instant()) == true) {
            passwordEncoder.matches(request.password, timingEqualizerHash)
            throw InvalidCredentialsException()
        }
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            // Commits in its own transaction so the increment survives this method's rollback.
            loginAttemptService.recordFailure(user.id)
            throw InvalidCredentialsException()
        }
        // Successful login clears any accumulated failures; skip the write on the common clean path.
        if (user.failedLoginAttempts != 0 || user.lockedUntil != null) loginAttemptService.clearFailures(user.id)
        return issueSession(user)
    }

    @Transactional
    fun refresh(rawRefreshToken: String): AuthSession {
        val rotated = tokenService.rotateRefreshToken(rawRefreshToken)
        val user = userRepository.findById(rotated.userId).orElseThrow { InvalidRefreshTokenException() }
        // An Art. 17 tombstone is not an account (ADR-20). The erasure deletes every refresh token, so
        // this should be unreachable — it is here so a future path that resurrects a token can never
        // mint a fresh 15-minute access token for a deleted account.
        if (user.isErased) throw InvalidRefreshTokenException()
        return AuthSession(
            user = UserResponse.from(user),
            accessToken = tokenService.issueAccessToken(user.id, emailVerified = user.verifiedAt != null),
            refreshToken = rotated.rawToken,
        )
    }

    @Transactional
    fun logout(rawRefreshToken: String?) {
        rawRefreshToken?.let(tokenService::revokeRefreshToken)
    }

    fun me(userId: UUID): UserResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        // Belt-and-braces with com.complyr.common.ErasedAccountFilter, which already rejects a tombstone's
        // still-valid access token before any controller runs. Kept because this is the call the dashboard
        // polls: if the filter is ever reordered or disabled, the session still collapses to a 401 here
        // rather than rendering a deleted account's shell.
        if (user.isErased) throw UnauthenticatedException()
        return UserResponse.from(user)
    }

    @Transactional
    fun verifyEmail(rawToken: String): UserResponse {
        val token = consumeToken(rawToken, TokenPurpose.EMAIL_VERIFICATION)
        val user = userRepository.findById(token.userId).orElseThrow { InvalidTokenException() }
        val wasUnverified = user.verifiedAt == null
        val verified = if (wasUnverified) user.copy(verifiedAt = clock.instant()) else user
        val saved = userRepository.save(verified)
        // Welcome only on the FIRST confirmation — a re-clicked verification link must not re-send it.
        // AFTER_COMMIT + async, so a mail failure can never roll back the verification (see listener).
        if (wasUnverified) {
            eventPublisher.publishEvent(WelcomeEmailRequested(saved.id, saved.email, saved.locale))
        }
        return UserResponse.from(saved)
    }

    /** Always succeeds (anti-enumeration): sends only for existing, unverified accounts. */
    @Transactional
    fun resendVerification(email: String) {
        val user = findLiveAccountByEmail(email) ?: return
        if (user.verifiedAt == null) sendVerificationEmail(user)
    }

    /** Always succeeds (anti-enumeration): sends only when the account exists. */
    @Transactional
    fun forgotPassword(email: String) {
        val user = findLiveAccountByEmail(email) ?: return
        // Only the most recently issued reset link may work: kill outstanding ones first.
        authTokenRepository.markAllUsedByUserIdAndPurpose(user.id, TokenPurpose.PASSWORD_RESET, clock.instant())
        val rawToken = createAuthToken(user.id, TokenPurpose.PASSWORD_RESET, properties.auth.resetTokenTtl)
        eventPublisher.publishEvent(PasswordResetEmailRequested(user.id, user.email, user.locale, rawToken))
    }

    @Transactional
    fun resetPassword(
        rawToken: String,
        newPassword: String,
    ) {
        val token = consumeToken(rawToken, TokenPurpose.PASSWORD_RESET)
        val user = userRepository.findById(token.userId).orElseThrow { InvalidTokenException() }
        // A tombstone has no password to reset (ADR-20). Unreachable via forgotPassword, which no longer
        // issues tokens for erased accounts — kept as the second half of the pair so a reset token minted
        // moments before the erasure committed cannot be redeemed after it.
        if (user.isErased) throw InvalidTokenException()
        userRepository.save(user.copy(passwordHash = hashPassword(newPassword)))
        tokenService.revokeAllForUser(user.id)
    }

    /**
     * Completes an email change (verify-new-first): swaps the account email to the parked
     * [UserEntity.pendingEmail] and clears it, then notifies the OLD address that the change happened.
     * The email_change token was mailed to the NEW address, so redeeming it proves control of that
     * address — which is the whole point of the flow (the request step only re-authenticated the current
     * password). Mirrors [verifyEmail]/[resetPassword]: consume the single-use token, load the account BY
     * the token's user id, refuse an Art. 17 tombstone.
     *
     * The swap runs through [saveEnsuringEmailUniqueness] because the address could have been registered
     * by another account in the window between request and confirmation — that surfaces as a 409
     * EMAIL_IN_USE rather than a PII-leaking 500. A confirmed change also (re-)stamps [UserEntity.verifiedAt]:
     * the click proves control of the address now bound to the account. A missing [UserEntity.pendingEmail]
     * (a superseded change whose token should already be spent) falls back to the same generic
     * [InvalidTokenException].
     */
    @Transactional
    fun confirmEmailChange(rawToken: String): UserResponse {
        val token = consumeToken(rawToken, TokenPurpose.EMAIL_CHANGE)
        val user = userRepository.findById(token.userId).orElseThrow { InvalidTokenException() }
        if (user.isErased) throw InvalidTokenException()
        val newEmail = user.pendingEmail ?: throw InvalidTokenException()
        val previousEmail = user.email
        val saved =
            saveEnsuringEmailUniqueness(
                user.copy(email = newEmail, pendingEmail = null, verifiedAt = clock.instant()),
            )
        // Security heads-up to the address that just lost control of the account. AFTER_COMMIT + async, so
        // a mail failure can never roll back the swap (see AuthEmailListener).
        eventPublisher.publishEvent(EmailChangedNoticeRequested(saved.id, previousEmail, saved.locale))
        return UserResponse.from(saved)
    }

    private fun issueSession(user: UserEntity): AuthSession =
        AuthSession(
            user = UserResponse.from(user),
            accessToken = tokenService.issueAccessToken(user.id, emailVerified = user.verifiedAt != null),
            refreshToken = tokenService.issueRefreshToken(user.id).rawToken,
        )

    /** Validates and single-uses a verification/reset token; generic failure (anti-enumeration). */
    private fun consumeToken(
        rawToken: String,
        purpose: TokenPurpose,
    ): AuthTokenEntity {
        val token =
            authTokenRepository.findByTokenHashAndPurpose(OpaqueTokens.sha256(rawToken), purpose)
                ?: throw InvalidTokenException()
        val now = clock.instant()
        if (token.usedAt != null || token.expiresAt <= now) throw InvalidTokenException()
        return authTokenRepository.save(token.copy(usedAt = now))
    }

    private fun sendVerificationEmail(user: UserEntity) {
        val rawToken = createAuthToken(user.id, TokenPurpose.EMAIL_VERIFICATION, properties.auth.verificationTokenTtl)
        eventPublisher.publishEvent(VerificationEmailRequested(user.id, user.email, user.locale, rawToken))
    }

    private fun createAuthToken(
        userId: UUID,
        purpose: TokenPurpose,
        ttl: Duration,
    ): String {
        val raw = OpaqueTokens.generate()
        authTokenRepository.save(
            AuthTokenEntity(
                userId = userId,
                tokenHash = OpaqueTokens.sha256(raw),
                purpose = purpose,
                expiresAt = clock.instant().plus(ttl),
                createdAt = clock.instant(),
            ),
        )
        return raw
    }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    /**
     * Looks up an account by email, treating an Art. 17 tombstone as no account at all (ADR-20).
     *
     * The erasure rewrites the address to `erased-<user id>@erased.invalid`, and that user id is printed in
     * the customer's own Art. 20 export — so the tombstone's email is *derivable by anyone who ever held
     * the export*, including a since-departed employee. Without this, `forgot-password` would happily mint
     * a reset token for it and hand back a working login to the shell of a deleted account.
     *
     * Every email-keyed entry point routes through here rather than checking [UserEntity.isErased]
     * individually, so a new one cannot silently omit the check.
     */
    private fun findLiveAccountByEmail(email: String): UserEntity? =
        userRepository.findByEmail(normalizeEmail(email))?.takeUnless { it.isErased }

    private fun hashPassword(rawPassword: String): String =
        requireNotNull(passwordEncoder.encode(rawPassword)) { "Password encoder returned null" }

    companion object {
        private const val TIMING_EQUALIZER_PASSWORD = "complyr-login-timing-equalizer"
        private const val UNIQUE_EMAIL_CONSTRAINT = "uq_users_email"
    }
}
