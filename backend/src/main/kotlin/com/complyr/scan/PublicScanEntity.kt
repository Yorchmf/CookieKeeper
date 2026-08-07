package com.complyr.scan

import jakarta.persistence.Column
import jakarta.persistence.Convert
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
    // Distinct marketing third-party trackers observed by the crawl (count only — raw hosts never
    // stored). Null until the scan completes; read as 0 by [ComplianceAnalyzer] on in-flight rows.
    @Column(name = "marketing_tracker_count")
    val marketingTrackerCount: Int? = null,
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

    /**
     * Try to take the transaction-scoped advisory lock [key], returning true only to the caller that
     * acquired it. Leader-guards the scheduled retention prune across backend replicas (see
     * [PublicScanReaper]): a losing caller skips its run. Held for the rest of the current transaction
     * and released automatically at commit/rollback — so it must be called from within a transaction
     * (the reaper runs each batch inside its own `TransactionTemplate`), never on its own.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    fun tryAcquireAdvisoryXactLock(
        @Param("key") key: Long,
    ): Boolean

    /**
     * Delete up to [batchSize] scans whose TTL horizon has passed ([expiresAt] < [cutoff]), returning
     * the number removed; [PublicScanCookieEntity] rows cascade via the FK's `ON DELETE CASCADE`. The
     * reaper calls this in a loop (one transaction per batch) so a backlog drains in bounded chunks
     * instead of one long DELETE that pins the vacuum horizon — see [PublicScanReaper].
     *
     * The inner `SELECT ctid ... ORDER BY expires_at LIMIT` walks `idx_public_scans_expires_at` to
     * pick the oldest-expiring [batchSize] rows and deletes them by physical row id, which is why this
     * must be native (`ctid` is not JPA-mapped). No `SKIP LOCKED` is needed: the reaper holds a
     * per-batch advisory lock, so no two batches ever target overlapping rows concurrently. These
     * rows are replaceable acquisition-funnel data, not append-only audit evidence, so DELETE is
     * allowed here — unlike `consent_events`.
     */
    @Modifying
    @Query(
        value =
            "DELETE FROM public_scans WHERE ctid IN " +
                "(SELECT ctid FROM public_scans WHERE expires_at < :cutoff " +
                "ORDER BY expires_at LIMIT :batchSize)",
        nativeQuery = true,
    )
    fun deleteBatchExpiredBefore(
        @Param("cutoff") cutoff: Instant,
        @Param("batchSize") batchSize: Int,
    ): Int
}
