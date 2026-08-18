package eu.cookiekeeper.account

import eu.cookiekeeper.auth.AuthTokenEntity
import eu.cookiekeeper.auth.AuthTokenRepository
import eu.cookiekeeper.auth.EmailAlreadyRegisteredException
import eu.cookiekeeper.auth.OpaqueTokens
import eu.cookiekeeper.auth.TokenPurpose
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.auth.dto.UserResponse
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.notify.EmailChangeRequested
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Starts an email change from inside an authenticated session ("verify the new address first", ADR-20).
 *
 * The request only PARKS the new address in [UserEntity.pendingEmail] and mails a single-use confirmation
 * link to it; the account's login email is untouched until that link is redeemed in
 * [eu.cookiekeeper.auth.AuthService.confirmEmailChange]. That split is the security property: a stolen session
 * cannot silently redirect a customer's login address, because (a) the current password is re-authenticated
 * here and (b) the swap needs an action on the NEW mailbox, which the attacker does not control. The old
 * address stays live throughout and receives a heads-up once the swap completes.
 */
@Service
class AccountEmailService(
    private val userRepository: UserRepository,
    private val authTokenRepository: AuthTokenRepository,
    private val passwordService: AccountPasswordService,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
) {
    /**
     * Re-authenticates [currentPassword], parks [newEmail], and mails the confirmation link to it. Returns
     * the refreshed user so the dashboard can render the "pending change to …" state from the response
     * rather than refetching `me`.
     *
     * A collision with an existing live account is reported up front as a 409 (better than mailing a link
     * that can only fail at confirm time); the authoritative check is still the unique index at swap time,
     * since a registration could land in the window between here and confirmation. Any outstanding
     * email_change token is invalidated first, so only the most recent link works — mirroring
     * [eu.cookiekeeper.auth.AuthService.forgotPassword].
     */
    @Transactional
    fun requestEmailChange(
        userId: UUID,
        newEmail: String,
        currentPassword: String,
    ): UserResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        passwordService.confirm(user, currentPassword) { CurrentPasswordIncorrectException() }

        val normalized = normalizeEmail(newEmail)
        if (normalized == user.email) throw NewEmailSameAsCurrentException()
        // Courtesy pre-check against a live account only; a tombstone's derivable @erased.invalid address is
        // not a real collision. Authenticated + re-authenticated, so this is no more of an enumeration
        // surface than signup's (documented) 409.
        val collides = userRepository.findByEmail(normalized)?.takeUnless { it.isErased } != null
        if (collides) throw EmailAlreadyRegisteredException()

        // Only the newest confirmation link may work: retire any outstanding ones first.
        authTokenRepository.markAllUsedByUserIdAndPurpose(user.id, TokenPurpose.EMAIL_CHANGE, clock.instant())
        val saved = userRepository.save(user.copy(pendingEmail = normalized))
        val rawToken = createAuthToken(user.id, TokenPurpose.EMAIL_CHANGE, properties.auth.emailChangeTokenTtl)
        // Sent to the NEW address — redeeming it is what proves control of it. AFTER_COMMIT + async.
        eventPublisher.publishEvent(EmailChangeRequested(saved.id, normalized, saved.locale, rawToken))
        return UserResponse.from(saved)
    }

    // Mirrors AuthService.createAuthToken / normalizeEmail deliberately: token minting stores only the
    // SHA-256 hash (the security-critical part lives in OpaqueTokens), and the account package does not
    // reach into AuthService's private helpers. Kept small and in sync by comment rather than a shared
    // collaborator so AuthService's constructor — and its test's call sites — stay untouched.
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
}
