package eu.cookiekeeper.policy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * One versioned, per-language rendered cookie policy (`policies`, V1 baseline). A single generation
 * appends one row per language, all sharing the same [version]; policies are never overwritten, so
 * `(site_id, version, language)` is unique. Consent events reference the [version] active at consent
 * time (audit requirement — docs §4.5), which is why regeneration bumps the version rather than
 * mutating [html] in place.
 *
 * [publishedAt] non-null marks a version visitors may see; drafts (future editing) would carry null.
 */
@Entity
@Table(name = "policies")
data class PolicyEntity(
    @Column(name = "site_id", nullable = false)
    val siteId: UUID,
    @Column(nullable = false)
    val version: Int,
    @Column(nullable = false)
    val language: String,
    @Column(nullable = false)
    val html: String,
    @Column(name = "published_at")
    val publishedAt: Instant? = null,
) {
    // Hibernate-generated UUIDv7 (mirrors BannerConfigEntity): id stays null until persist so Spring
    // Data save() routes to persist() (INSERT, no SELECT-before-INSERT), and v7 time-ordering keeps
    // the PK B-tree insert-locality friendly on the append path.
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null
}

interface PolicyRepository : JpaRepository<PolicyEntity, UUID> {
    /** Highest version row for a site (any language, published or not) — the basis for the next version. */
    fun findFirstBySiteIdOrderByVersionDesc(siteId: UUID): PolicyEntity?

    /** Highest published version row for a site (any language) — used to read/derive the current version. */
    fun findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId: UUID): PolicyEntity?

    /** All language rows of a specific version — the full set the current version exposes. */
    fun findBySiteIdAndVersion(
        siteId: UUID,
        version: Int,
    ): List<PolicyEntity>

    /**
     * Transaction-scoped Postgres advisory lock keyed on a site, taken at the top of [generate] so two
     * concurrent generations for one site serialize instead of both computing the same next version and
     * colliding on `uq_policies_site_version_language` (which would surface as a raw 500). The lock is
     * released automatically at commit/rollback; the wrapping `SELECT count(*)` just gives the native
     * query a mappable non-void result. See [eu.cookiekeeper.policy.PolicyService.generate].
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireSiteGenerationLock(
        @Param("key") key: Long,
    ): Long
}
