package com.complyr.account

import com.complyr.auth.TokenService
import com.complyr.auth.UserRepository
import com.complyr.common.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The account's control over its own sessions — currently just "sign out of all devices" for
 * `/settings/security`. Kept separate from [AccountPasswordService] because signing out everywhere touches no
 * credential column: it re-authenticates through that service's [AccountPasswordService.confirm] (so the same
 * login lockout applies) and then revokes every refresh token, without minting or rotating a password.
 *
 * What this does NOT do: invalidate access JWTs already issued to other devices. Those are stateless and stay
 * valid until they expire (≤ the access-token TTL); only the refresh tokens are killed immediately, so other
 * devices lose the ability to renew and drop out within that window. A true instant kill needs the deferred
 * JWT-revocation (jti deny-list) work — the `/settings/security` copy states this so the promise is honest.
 */
@Component
class AccountSessionService(
    private val userRepository: UserRepository,
    private val passwordService: AccountPasswordService,
    private val tokenService: TokenService,
) {
    private val log = LoggerFactory.getLogger(AccountSessionService::class.java)

    /**
     * Revokes every refresh token the account holds after re-authenticating with the current password.
     *
     * [currentPassword] is re-authenticated through [AccountPasswordService.confirm], so a wrong password
     * spends the shared login-lockout budget and is refused with [CurrentPasswordIncorrectException] (403) —
     * the same code the change-password form already maps. This session's own refresh token is revoked too;
     * the controller clears this browser's cookies in the same response so the dashboard returns the user to
     * sign in rather than stranding a browser whose refresh token is already dead.
     */
    @Transactional
    fun revokeAllSessions(
        userId: UUID,
        currentPassword: String,
    ) {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        passwordService.confirm(user, currentPassword) { CurrentPasswordIncorrectException() }
        tokenService.revokeAllForUser(user.id)
        log.info("Revoked all sessions for {} on request", user.id)
    }
}
