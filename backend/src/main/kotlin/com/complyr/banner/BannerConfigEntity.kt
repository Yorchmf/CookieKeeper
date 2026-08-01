package com.complyr.banner

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    /** Highest version row for a site (published or not) — the basis for the next version number. */
    fun findFirstBySiteIdOrderByVersionDesc(siteId: UUID): BannerConfigEntity?

    /**
     * Transaction-scoped Postgres advisory lock keyed on a site, taken before publishing a new version
     * so two concurrent saves for one site serialize instead of both computing the same next version and
     * colliding on `uq_banner_configs_site_version` (a raw 500). Released at commit/rollback; the wrapping
     * `SELECT count(*)` just gives the native query a mappable non-void result (mirrors PolicyRepository).
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireSitePublishLock(
        @Param("key") key: Long,
    ): Long
}
