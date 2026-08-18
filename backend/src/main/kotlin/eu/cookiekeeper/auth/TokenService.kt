package eu.cookiekeeper.auth

import eu.cookiekeeper.common.CookieKeeperProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A freshly rotated/issued refresh token: the raw value goes into the cookie, never at rest. */
data class IssuedRefreshToken(
    val userId: UUID,
    val rawToken: String,
)

/**
 * Access JWT issuing plus refresh-token lifecycle: issue, rotate-on-use, reuse detection.
 *
 * Reuse detection: presenting an already-revoked refresh token revokes every refresh token
 * of that user (the whole family), except within a short grace window after rotation to
 * tolerate parallel-refresh races from multiple tabs.
 */
@Service
class TokenService(
    private val jwtEncoder: JwtEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    // Reuse-detection revocation must survive the InvalidRefreshTokenException rollback,
    // so it runs in its own (REQUIRES_NEW) transaction.
    private val independentTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun issueAccessToken(
        userId: UUID,
        emailVerified: Boolean,
    ): String {
        val now = clock.instant()
        val claims =
            JwtClaimsSet
                .builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.auth.accessTokenTtl))
                .claim(CLAIM_EMAIL_VERIFIED, emailVerified)
                .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    @Transactional
    fun issueRefreshToken(
        userId: UUID,
        rotatedFrom: UUID? = null,
    ): IssuedRefreshToken {
        val raw = OpaqueTokens.generate()
        refreshTokenRepository.save(
            RefreshTokenEntity(
                userId = userId,
                tokenHash = OpaqueTokens.sha256(raw),
                expiresAt = clock.instant().plus(properties.auth.refreshTokenTtl),
                rotatedFrom = rotatedFrom,
                createdAt = clock.instant(),
            ),
        )
        return IssuedRefreshToken(userId = userId, rawToken = raw)
    }

    /**
     * Rotates a presented refresh token: revokes it and issues a successor.
     * Revoked-token reuse outside the grace window revokes the whole family.
     */
    @Transactional
    fun rotateRefreshToken(rawToken: String): IssuedRefreshToken {
        val existing =
            refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256(rawToken))
                ?: throw InvalidRefreshTokenException()
        val now = clock.instant()
        if (existing.expiresAt <= now) throw InvalidRefreshTokenException()

        if (existing.revokedAt != null) return handleRevokedTokenReuse(existing, now)

        refreshTokenRepository.save(existing.copy(revokedAt = now))
        return issueRefreshToken(existing.userId, rotatedFrom = existing.id)
    }

    private fun handleRevokedTokenReuse(
        existing: RefreshTokenEntity,
        now: Instant,
    ): IssuedRefreshToken {
        val revokedAt = requireNotNull(existing.revokedAt) { "token must be revoked here" }
        val withinGrace = Duration.between(revokedAt, now) < properties.auth.refreshReuseGrace
        // The grace applies ONLY to rotation-revoked tokens (a successor exists). Logout and
        // revoke-all (password reset) leave no successor, so those revocations are never undone.
        if (withinGrace && refreshTokenRepository.existsByRotatedFrom(existing.id)) {
            // Parallel-refresh race (e.g. two tabs): tolerate the immediately-preceding token.
            return issueRefreshToken(existing.userId, rotatedFrom = existing.id)
        }
        // Reuse detected: intentionally revoke ALL the user's refresh tokens (fail-closed),
        // not just the rotation chain, in an independent transaction so the revocation
        // survives the exception-triggered rollback.
        independentTx.executeWithoutResult {
            refreshTokenRepository.revokeAllByUserId(existing.userId, now)
        }
        throw InvalidRefreshTokenException()
    }

    /** Revokes the presented refresh token if it exists (logout). Silently ignores unknown tokens. */
    @Transactional
    fun revokeRefreshToken(rawToken: String) {
        val existing = refreshTokenRepository.findByTokenHash(OpaqueTokens.sha256(rawToken)) ?: return
        if (existing.revokedAt == null) {
            refreshTokenRepository.save(existing.copy(revokedAt = clock.instant()))
        }
    }

    /** Revokes every active refresh token of the user (e.g. after a password reset). */
    @Transactional
    fun revokeAllForUser(userId: UUID) {
        refreshTokenRepository.revokeAllByUserId(userId, clock.instant())
    }

    companion object {
        const val CLAIM_EMAIL_VERIFIED = "email_verified"
    }
}
