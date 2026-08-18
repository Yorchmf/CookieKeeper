package eu.cookiekeeper.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Rotating refresh token. Only the SHA-256 hash of the opaque token is stored;
 * [rotatedFrom] links a rotation chain for reuse detection.
 */
@Entity
@Table(name = "refresh_tokens")
data class RefreshTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "token_hash", nullable = false)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "rotated_from")
    val rotatedFrom: UUID? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "revoked_at")
    val revokedAt: Instant? = null,
)

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    /** True when a successor token was rotated from [rotatedFrom] — i.e. the token was rotation-revoked. */
    fun existsByRotatedFrom(rotatedFrom: UUID): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update RefreshTokenEntity t set t.revokedAt = :now " +
            "where t.userId = :userId and t.revokedAt is null",
    )
    fun revokeAllByUserId(
        @Param("userId") userId: UUID,
        @Param("now") now: Instant,
    ): Int
}
