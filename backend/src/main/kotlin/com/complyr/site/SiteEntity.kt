package com.complyr.site

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    fun findBySiteKeyAndStatus(
        siteKey: String,
        status: SiteStatus,
    ): SiteEntity?

    fun existsByUserIdAndDomainAndStatus(
        userId: UUID,
        domain: String,
        status: SiteStatus,
    ): Boolean

    /** Active-site count for the plan site-cap guard (see [com.complyr.billing.EntitlementService]). */
    fun countByUserIdAndStatus(
        userId: UUID,
        status: SiteStatus,
    ): Long

    /**
     * Transaction-scoped Postgres advisory lock keyed on a user, taken at the top of the site-cap guard
     * so concurrent site creations for one account serialize instead of both reading `count < cap` and
     * each inserting (a check-then-act race that could let a plan exceed its `maxSites`). Released at
     * commit/rollback; the wrapping `SELECT count(*)` just gives the native query a mappable result.
     * Mirrors [com.complyr.policy.PolicyEntity]'s per-site lock. See
     * [com.complyr.billing.EntitlementService.requireCanAddSite].
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserSiteLock(
        @Param("key") key: Long,
    ): Long
}
