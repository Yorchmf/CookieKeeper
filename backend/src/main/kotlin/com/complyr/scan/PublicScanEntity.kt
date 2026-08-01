package com.complyr.scan

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * An anonymous free scan (`public_scans`) — the marketing-site acquisition funnel, deliberately
 * separate from [ScanEntity]: no owning site, a short TTL, an opaque read token, and an [ipHash]
 * for abuse analysis only. See docs/anonymous-scan-funnel.md (ADR-12).
 *
 * It reuses the scan *engine* (crawl, [ScanCookieMapper] caps, [ScanStatus] lifecycle) but not the
 * owned-scan *schema*. State transitions are applied immutably via `copy(...)` + save, never by
 * mutating a loaded instance (mirrors [ScanEntity] / [com.complyr.site.SiteEntity]).
 *
 * The result is read by [publicToken] only, never by [id]: [id] is a randomly-assigned surrogate key
 * with no capability semantics, and the token is the unguessable secret that authorizes a read.
 */
@Entity
@Table(name = "public_scans")
data class PublicScanEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val domain: String,
    @Convert(converter = ScanStatusConverter::class)
    @Column(nullable = false)
    val status: ScanStatus = ScanStatus.QUEUED,
    // Opaque, unguessable read key (auth.OpaqueTokens.generate). Unique — it is the read capability.
    @Column(name = "public_token", nullable = false, updatable = false)
    val publicToken: String,
    // Captured only when the visitor requests the detailed report (email gate); null until then.
    @Column
    val email: String? = null,
    // Rotating-salt hash of the requester IP for abuse analysis (never the raw IP — CLAUDE.md #4).
    @Column(name = "ip_hash")
    val ipHash: String? = null,
    @Column(name = "error")
    val error: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    // Retention horizon — the reaper (slice G) purges rows past this; cookies cascade.
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
)

interface PublicScanRepository : JpaRepository<PublicScanEntity, UUID> {
    /** The read path: fetch a scan by its opaque token (backed by the unique index on `public_token`). */
    fun findByPublicToken(publicToken: String): PublicScanEntity?

    /**
     * 24h per-domain cache: the most recent scan for [domain] in [status] created at/after
     * [createdAtFrom]. Scoped by [status] so the cache only ever serves a completed result
     * ([ScanStatus.DONE]) — a recent `failed`/`running` row must neither be shown as the verdict nor
     * pin a domain for 24h with no retry. Backed by `idx_public_scans_domain_created_at` (status is a
     * filter on the tiny per-domain result set; no composite index needed at this volume).
     */
    fun findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
        domain: String,
        status: ScanStatus,
        createdAtFrom: Instant,
    ): PublicScanEntity?

    /**
     * How many scans a single requester (by rotating-salt [ipHash]) currently has in flight — the
     * abuse-cap probe used before enqueuing a new crawl. Callers pass the in-flight statuses
     * ([ScanStatus.QUEUED], [ScanStatus.RUNNING]); terminal `done`/`failed` rows never count.
     */
    fun countByIpHashAndStatusIn(
        ipHash: String,
        statuses: Collection<ScanStatus>,
    ): Long
}
