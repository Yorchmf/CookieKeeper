package com.complyr.scan

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Lifecycle of a scan RESULT record (`scans.status`), distinct from the queue's delivery state. */
enum class ScanStatus(
    val dbValue: String,
) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    ;

    companion object {
        fun fromDbValue(value: String): ScanStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown scan status: $value")
    }
}

@Converter
class ScanStatusConverter : AttributeConverter<ScanStatus, String> {
    override fun convertToDatabaseColumn(attribute: ScanStatus): String = attribute.dbValue

    override fun convertToEntityAttribute(dbData: String): ScanStatus = ScanStatus.fromDbValue(dbData)
}

/** What caused a scan to be enqueued (audit/provenance on `scans.trigger_source`). */
enum class ScanTrigger(
    val dbValue: String,
) {
    SITE_ADDED("site_added"),
    MANUAL("manual"),
    SCHEDULED("scheduled"),
    ;

    companion object {
        fun fromDbValue(value: String): ScanTrigger =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown scan trigger: $value")
    }
}

@Converter
class ScanTriggerConverter : AttributeConverter<ScanTrigger, String> {
    override fun convertToDatabaseColumn(attribute: ScanTrigger): String = attribute.dbValue

    override fun convertToEntityAttribute(dbData: String): ScanTrigger = ScanTrigger.fromDbValue(dbData)
}

/**
 * A scan of a site (`scans`) — the durable, user-visible record the dashboard shows. Its
 * [status] tracks the crawl lifecycle (queued -> running -> done/failed); the queue mechanics
 * (retries, visibility) live on the sibling `jobs` row that references this scan's [id].
 *
 * State transitions are applied immutably via `copy(...)` + save (mirrors [com.complyr.site.SiteEntity]),
 * never by mutating a loaded instance.
 */
@Entity
@Table(name = "scans")
data class ScanEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "site_id", nullable = false)
    val siteId: UUID,
    @Convert(converter = ScanStatusConverter::class)
    @Column(nullable = false)
    val status: ScanStatus = ScanStatus.QUEUED,
    @Convert(converter = ScanTriggerConverter::class)
    @Column(name = "trigger_source", nullable = false)
    val trigger: ScanTrigger,
    @Column(name = "started_at")
    val startedAt: Instant? = null,
    @Column(name = "finished_at")
    val finishedAt: Instant? = null,
    @Column(name = "pages_crawled")
    val pagesCrawled: Int? = null,
    // Distinct marketing third-party trackers observed by the crawl (count only — raw hosts never
    // stored). Null until a scan completes; read as 0 by [ComplianceAnalyzer] on historical/in-flight rows.
    @Column(name = "marketing_tracker_count")
    val marketingTrackerCount: Int? = null,
    @Column(name = "error")
    val error: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)

interface ScanRepository : JpaRepository<ScanEntity, UUID> {
    /** Newest-first scans for a site (backed by `idx_scans_site_id_created_at`), bounded by [pageable]. */
    fun findBySiteIdOrderByCreatedAtDesc(
        siteId: UUID,
        pageable: Pageable,
    ): List<ScanEntity>

    /**
     * How many scans a site has ever had. The Art. 20 export lists only the most recent page of scans, and
     * publishes this alongside so a truncated list is visible as truncated rather than looking complete.
     */
    fun countBySiteId(siteId: UUID): Long

    /** One scan scoped to its site — the read path pairs this with a site-ownership check. */
    fun findByIdAndSiteId(
        id: UUID,
        siteId: UUID,
    ): ScanEntity?

    /**
     * The site's most recent scan of ANY status — what the re-scan scheduler measures due-ness from
     * (`max(created_at)`, no status filter), so a queued or failed scan pushes the next scheduled one out
     * exactly as a completed one does. Rides `idx_scans_site_id_created_at`; null for a never-scanned site.
     */
    fun findFirstBySiteIdOrderByCreatedAtDesc(siteId: UUID): ScanEntity?

    /**
     * The site's most recent scan in a given status — the policy generator reads this with
     * [ScanStatus.DONE] to source the cookies it lists (null when the site has never completed a scan,
     * in which case the policy legitimately states none were found).
     */
    fun findFirstBySiteIdAndStatusOrderByCreatedAtDesc(
        siteId: UUID,
        status: ScanStatus,
    ): ScanEntity?

    /**
     * The site's most recent scan in a given status STRICTLY OLDER than [createdAt] — the previous-result
     * baseline [ScanCompletionNotifier] diffs a just-finished scan against. The upper bound is what makes
     * it "previous": that notifier runs after the completion commit, so the scan it is reporting on is
     * itself already `done`, and the unbounded sibling above would hand back that same row. Rides
     * `idx_scans_site_id_created_at` (V7); null when the site has no earlier scan in that status.
     */
    fun findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
        siteId: UUID,
        status: ScanStatus,
        createdAt: Instant,
    ): ScanEntity?

    /**
     * Whether the site already has a live (queued or running) scan — the one-live-scan-per-site rule
     * behind [ScanAlreadyInProgressException]. Backed by the partial `idx_scans_site_id_live` (V16), so it
     * costs an index probe over the handful of live rows rather than a walk of the site's whole history.
     *
     * Must be called under [acquireSiteScanLock] to be a decision rather than a guess: without it two
     * concurrent requests can both read "no live scan" before either inserts.
     */
    fun existsBySiteIdAndStatusIn(
        siteId: UUID,
        statuses: Collection<ScanStatus>,
    ): Boolean

    /**
     * Take a transaction-scoped per-site advisory lock, serializing the check-then-enqueue in
     * [ScanRequestService]. Copied from [com.complyr.site.SiteRepository.acquireUserSiteLock]: the lock
     * releases automatically on commit/rollback, and the wrapping `SELECT count(*)` just gives the native
     * query a mappable result.
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireSiteScanLock(
        @Param("key") key: Long,
    ): Long

    /**
     * Non-blocking sibling of [acquireSiteScanLock] for the scheduled re-scan job: returns false when
     * another instance already holds the lock, so a second scheduler exits immediately instead of queueing
     * up behind the first. Mirrors [com.complyr.scan.PublicScanRepository.tryAcquireAdvisoryXactLock].
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    fun tryAcquireAdvisoryXactLock(
        @Param("key") key: Long,
    ): Boolean
}
