package com.complyr.policy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Per-site policy settings (`policy_settings`, V11): the customer's business [details] plus the
 * stable, opaque [publicId] that addresses the hosted page `/p/{publicId}`. One row per site
 * ([siteId] is the PK). [publicId] is generated once at creation and never rotated, so republishing
 * (which bumps `policies.version`) never changes the URL the customer has shared.
 *
 * State updates are immutable `copy(...)` + save (mirrors [com.complyr.site.SiteEntity]); [publicId]
 * and [createdAt] are carried through so they stay stable across detail edits.
 */
@Entity
@Table(name = "policy_settings")
data class PolicySettingsEntity(
    @Id
    @Column(name = "site_id", nullable = false, updatable = false)
    val siteId: UUID,
    @Column(name = "public_id", nullable = false, updatable = false)
    val publicId: UUID = UUID.randomUUID(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    val details: PolicyDetails,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)

interface PolicySettingsRepository : JpaRepository<PolicySettingsEntity, UUID> {
    /** Resolve a site's stable public id → its settings, for the hosted-page read path. */
    fun findByPublicId(publicId: UUID): PolicySettingsEntity?
}
