package com.complyr.scan

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A single cookie observed during a scan (`scan_cookies`, V1 baseline). Slice 2 records the raw
 * observation — [name], [domain], [expiry] — for a *before-consent* crawl. [category], [provider],
 * and [isKnown] stay null/false until slice 3 classifies each cookie against the signature DB.
 *
 * Not audit evidence: unlike `consent_events`, scan findings are replaceable, so a re-run of the same
 * scan deletes the previous attempt's rows before inserting fresh ones (see [ScanCookieRepository]).
 */
@Entity
@Table(name = "scan_cookies")
data class ScanCookieEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "scan_id", nullable = false)
    val scanId: UUID,
    @Column(nullable = false)
    val name: String,
    @Column
    val domain: String? = null,
    // Kept as text: the source value is a session flag or an epoch expiry, not a DB timestamp — we
    // store it verbatim for display and defer any parsing to the classification/UI layer.
    @Column
    override val expiry: String? = null,
    @Column
    override val category: String? = null,
    @Column
    val provider: String? = null,
    @Column(name = "is_known", nullable = false)
    override val isKnown: Boolean = false,
    // Transport flags observed by the crawl (V17). A non-essential cookie missing both is served in the
    // clear and script-readable — an insecure-flag finding in [ComplianceAnalyzer].
    @Column(nullable = false)
    override val secure: Boolean = false,
    @Column(name = "http_only", nullable = false)
    override val httpOnly: Boolean = false,
) : ScanCookieView

interface ScanCookieRepository : JpaRepository<ScanCookieEntity, UUID> {
    fun findByScanId(scanId: UUID): List<ScanCookieEntity>

    /**
     * Every cookie row for a set of scans in one query — the scan-history list batches its per-row
     * new-cookie diff through this to stay at a single read instead of an N+1 over the page.
     */
    fun findByScanIdIn(scanIds: Collection<UUID>): List<ScanCookieEntity>

    /** Drop a prior attempt's findings so a re-run replaces (not duplicates) this scan's cookies. */
    @Transactional
    fun deleteByScanId(scanId: UUID): Long

    // Deferred optimization (reviewed, low impact): the assigned-UUID @Id makes Spring Data's isNew()
    // always false, so saveAll routes each row through merge() (a SELECT-before-INSERT) rather than
    // persist(), and this derived deleteBy is a load-then-delete. Harmless at the current volume
    // (≤ dozens of short-lived rows per scan, deleted wholesale on re-run). If scan_cookies ever grows
    // hot: implement Persistable<UUID> with a @Transient isNew flag and switch to a @Modifying bulk
    // delete + hibernate.jdbc.batch_size. Not worth the entity lifecycle state now (YAGNI).
}
