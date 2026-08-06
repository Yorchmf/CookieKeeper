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

/**
 * Which proof of domain control was accepted (ADR-17). Provenance for an un-backfillable transition:
 * once a site is verified we can never re-derive *how*, and the dashboard tells the customer which
 * proof it found. Persisted values are pinned by `ck_sites_verification_method` (V15).
 */
enum class VerificationMethod(
    val dbValue: String,
) {
    /** The embed snippet was found on the domain's homepage — i.e. the widget is installed. */
    SNIPPET("snippet"),

    /** A `_complyr.<domain>` TXT record carries the site key — zone control, the ACME DNS-01 bar. */
    DNS_TXT("dns_txt"),
    ;

    companion object {
        fun fromDbValue(value: String): VerificationMethod =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown verification method: $value")
    }
}

@Converter
class VerificationMethodConverter : AttributeConverter<VerificationMethod?, String?> {
    override fun convertToDatabaseColumn(attribute: VerificationMethod?): String? = attribute?.dbValue

    override fun convertToEntityAttribute(dbData: String?): VerificationMethod? = dbData?.let(VerificationMethod::fromDbValue)
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
    // Paired by `ck_sites_verification_method_pairs` (V15): set and cleared together, always.
    @Column(name = "verified_at")
    val verifiedAt: Instant? = null,
    @Convert(converter = VerificationMethodConverter::class)
    @Column(name = "verification_method")
    val verificationMethod: VerificationMethod? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

/**
 * One row of [SiteRepository.findRescanCandidates]: a site the scheduled re-scan job may enqueue this
 * run, its owner (so the job batch-resolves the plan without an N+1 — see
 * [com.complyr.billing.EntitlementService.resolveAll]), and the timestamp of its most recent scan
 * ([lastScanAt] is null for a site that has never been scanned). A Spring Data interface projection over
 * the native query.
 */
interface RescanCandidate {
    val siteId: UUID
    val userId: UUID
    val lastScanAt: Instant?
}

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

    /**
     * Active sites eligible for a scheduled re-scan ([com.complyr.scan.ScheduledRescanJob]): no
     * queued/running scan in flight, and whose most recent scan is older than [cutoff] — or which have
     * never been scanned. Ordered oldest-first (never-scanned first) and capped at [batchSize] so a
     * backlog drains over successive nightly runs rather than hitting the single-Chromium worker as one
     * burst.
     *
     * [cutoff] is a COARSE pre-filter keyed on the SHORTEST plan cadence (7 days, asserted by a guard
     * test on [com.complyr.billing.RescanFrequency]); the exact per-plan cadence is applied in Kotlin so
     * no plan logic lives in SQL. The lateral rides `idx_scans_site_id_created_at` (V7) for the per-site
     * max; the `NOT EXISTS` rides the partial `idx_scans_site_id_live` (V16). Aliases are lowercased by
     * Postgres and matched case-insensitively to [RescanCandidate]'s properties by Spring Data.
     */
    @Query(
        value = """
            SELECT s.id AS siteId, s.user_id AS userId, ls.last_scan_at AS lastScanAt
            FROM sites s
            LEFT JOIN LATERAL (
                SELECT max(sc.created_at) AS last_scan_at
                FROM scans sc
                WHERE sc.site_id = s.id
            ) ls ON true
            WHERE s.status = 'active'
              AND NOT EXISTS (
                  SELECT 1 FROM scans live
                  WHERE live.site_id = s.id AND live.status IN ('queued', 'running')
              )
              AND (ls.last_scan_at IS NULL OR ls.last_scan_at < :cutoff)
            ORDER BY ls.last_scan_at ASC NULLS FIRST
            LIMIT :batchSize
        """,
        nativeQuery = true,
    )
    fun findRescanCandidates(
        @Param("cutoff") cutoff: Instant,
        @Param("batchSize") batchSize: Int,
    ): List<RescanCandidate>
}
