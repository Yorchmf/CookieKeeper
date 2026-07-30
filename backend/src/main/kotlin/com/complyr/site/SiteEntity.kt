package com.complyr.site

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class SiteStatus(
    val dbValue: String,
) {
    ACTIVE("active"),
    ARCHIVED("archived"),
    ;

    companion object {
        fun fromDbValue(value: String): SiteStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown site status: $value")
    }
}

@Converter
class SiteStatusConverter : AttributeConverter<SiteStatus, String> {
    override fun convertToDatabaseColumn(attribute: SiteStatus): String = attribute.dbValue

    override fun convertToEntityAttribute(dbData: String): SiteStatus = SiteStatus.fromDbValue(dbData)
}

@Entity
@Table(name = "sites")
data class SiteEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(nullable = false)
    val domain: String,
    @Column(name = "site_key", nullable = false)
    val siteKey: String,
    @Convert(converter = SiteStatusConverter::class)
    @Column(nullable = false)
    val status: SiteStatus = SiteStatus.ACTIVE,
    @Column(name = "verified_at")
    val verifiedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

interface SiteRepository : JpaRepository<SiteEntity, UUID> {
    fun findAllByUserIdAndStatus(
        userId: UUID,
        status: SiteStatus,
    ): List<SiteEntity>

    fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): SiteEntity?

    fun existsByUserIdAndDomainAndStatus(
        userId: UUID,
        domain: String,
        status: SiteStatus,
    ): Boolean
}
