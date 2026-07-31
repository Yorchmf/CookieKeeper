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

    /** One scan scoped to its site — the read path pairs this with a site-ownership check. */
    fun findByIdAndSiteId(
        id: UUID,
        siteId: UUID,
    ): ScanEntity?
}
