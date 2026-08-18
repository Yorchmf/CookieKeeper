package eu.cookiekeeper.auth

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

enum class TokenPurpose(
    val dbValue: String,
) {
    EMAIL_VERIFICATION("email_verification"),
    PASSWORD_RESET("password_reset"),
    EMAIL_CHANGE("email_change"),
    ;

    companion object {
        fun fromDbValue(value: String): TokenPurpose =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown token purpose: $value")
    }
}

@Converter
class TokenPurposeConverter : AttributeConverter<TokenPurpose, String> {
    override fun convertToDatabaseColumn(attribute: TokenPurpose): String = attribute.dbValue

    override fun convertToEntityAttribute(dbData: String): TokenPurpose = TokenPurpose.fromDbValue(dbData)
}

/**
 * Single-use token for email verification / password reset / email change.
 * Only the SHA-256 hash of the opaque token is stored.
 */
@Entity
@Table(name = "auth_tokens")
data class AuthTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "token_hash", nullable = false)
    val tokenHash: String,
    @Convert(converter = TokenPurposeConverter::class)
    @Column(nullable = false)
    val purpose: TokenPurpose,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    val usedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

interface AuthTokenRepository : JpaRepository<AuthTokenEntity, UUID> {
    fun findByTokenHashAndPurpose(
        tokenHash: String,
        purpose: TokenPurpose,
    ): AuthTokenEntity?

    /** Invalidates every outstanding token of [purpose] for the user (backed by idx_auth_tokens_user_purpose). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "update AuthTokenEntity t set t.usedAt = :now " +
            "where t.userId = :userId and t.purpose = :purpose and t.usedAt is null",
    )
    fun markAllUsedByUserIdAndPurpose(
        @Param("userId") userId: UUID,
        @Param("purpose") purpose: TokenPurpose,
        @Param("now") now: Instant,
    ): Int
}
