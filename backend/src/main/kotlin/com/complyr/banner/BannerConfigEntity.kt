package com.complyr.banner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A single versioned banner configuration for a site (`banner_configs`). New versions are
 * appended — a config is never overwritten — so `(site_id, version)` is unique. [publishedAt]
 * non-null marks the version the widget should serve; drafts (future dashboard editing) would
 * carry a null [publishedAt].
 */
@Entity
@Table(name = "banner_configs")
data class BannerConfigEntity(
    @Column(name = "site_id", nullable = false)
    val siteId: UUID,
    @Column(nullable = false)
    val version: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_jsonb", nullable = false)
    val config: BannerConfigDocument,
    @Column(name = "published_at")
    val publishedAt: Instant? = null,
) {
    // Hibernate-generated UUIDv7 (mirrors ConsentEventEntity): the id stays null until persist, so
    // Spring Data save() routes to persist() (INSERT) instead of merge() — no SELECT-before-INSERT —
    // and v7's time-ordering keeps the PK B-tree locality-friendly.
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null
}

interface BannerConfigRepository : JpaRepository<BannerConfigEntity, UUID> {
    /** The current published config for a site — highest version with a non-null `published_at`. */
    fun findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId: UUID): BannerConfigEntity?
}
