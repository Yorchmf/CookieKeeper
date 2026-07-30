package com.complyr.auth

import com.complyr.auth.dto.LoginRequest
import com.complyr.auth.dto.SignupRequest
import com.complyr.auth.dto.UserResponse
import com.complyr.common.ComplyrProperties
import com.complyr.common.UnauthenticatedException
import com.complyr.notify.PasswordResetEmailRequested
import com.complyr.notify.VerificationEmailRequested
import org.springframework.context.ApplicationEventPublisher
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
            userRepository.save(
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

    @Transactional
    fun login(request: LoginRequest): AuthSession {
        val user = userRepository.findByEmail(normalizeEmail(request.email))
        if (user == null) {
            // Burn the same bcrypt cost as the known-email branch (anti-enumeration timing).
            passwordEncoder.matches(request.password, timingEqualizerHash)
            throw InvalidCredentialsException()
        }
        if (!passwordEncoder.matches(request.password, user.passwordHash)) throw InvalidCredentialsException()
        return issueSession(user)
    }

    @Transactional
    fun refresh(rawRefreshToken: String): AuthSession {
        val rotated = tokenService.rotateRefreshToken(rawRefreshToken)
        val user = userRepository.findById(rotated.userId).orElseThrow { InvalidRefreshTokenException() }
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
        return UserResponse.from(user)
    }

    @Transactional
    fun verifyEmail(rawToken: String): UserResponse {
        val token = consumeToken(rawToken, TokenPurpose.EMAIL_VERIFICATION)
        val user = userRepository.findById(token.userId).orElseThrow { InvalidTokenException() }
        val verified = if (user.verifiedAt == null) user.copy(verifiedAt = clock.instant()) else user
        return UserResponse.from(userRepository.save(verified))
    }

    /** Always succeeds (anti-enumeration): sends only for existing, unverified accounts. */
    @Transactional
    fun resendVerification(email: String) {
        val user = userRepository.findByEmail(normalizeEmail(email)) ?: return
        if (user.verifiedAt == null) sendVerificationEmail(user)
    }

    /** Always succeeds (anti-enumeration): sends only when the account exists. */
    @Transactional
    fun forgotPassword(email: String) {
        val user = userRepository.findByEmail(normalizeEmail(email)) ?: return
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
        userRepository.save(user.copy(passwordHash = hashPassword(newPassword)))
        tokenService.revokeAllForUser(user.id)
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

    private fun hashPassword(rawPassword: String): String =
        requireNotNull(passwordEncoder.encode(rawPassword)) { "Password encoder returned null" }

    companion object {
        private const val TIMING_EQUALIZER_PASSWORD = "complyr-login-timing-equalizer"
    }
}
