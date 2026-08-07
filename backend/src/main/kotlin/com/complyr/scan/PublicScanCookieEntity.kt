package com.complyr.scan

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A single cookie observed during an anonymous free scan (`public_scan_cookies`) — the same classified
 * shape as [ScanCookieEntity] but FK'd to [PublicScanEntity] so a purge or re-run cleans it up.
 *
 * Not audit evidence: like `scan_cookies`, these findings are replaceable, so a re-run deletes the
 * previous attempt's rows before inserting fresh ones (see [PublicScanCookieRepository]). The same
 * per-scan caps (bounded count, truncated names — [ScanCookieMapper]) apply on the write path.
 */
@Entity
@Table(name = "public_scan_cookies")
data class PublicScanCookieEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "public_scan_id", nullable = false)
    val publicScanId: UUID,
    @Column(nullable = false)
    val name: String,
    @Column
    val domain: String? = null,
    // Kept as text (session flag or epoch expiry, not a DB timestamp) — mirrors ScanCookieEntity.
    @Column
    override val expiry: String? = null,
    @Column
    override val category: String? = null,
    @Column
    val provider: String? = null,
    @Column(name = "is_known", nullable = false)
    override val isKnown: Boolean = false,
    // Transport flags — mirrors ScanCookieEntity (V17); scored identically by [ComplianceAnalyzer].
    @Column(nullable = false)
    override val secure: Boolean = false,
    @Column(name = "http_only", nullable = false)
    override val httpOnly: Boolean = false,
) : ScanCookieView

interface PublicScanCookieRepository : JpaRepository<PublicScanCookieEntity, UUID> {
    fun findByPublicScanId(publicScanId: UUID): List<PublicScanCookieEntity>

    /** Drop a prior attempt's findings so a re-run replaces (not duplicates) this scan's cookies. */
    @Transactional
    fun deleteByPublicScanId(publicScanId: UUID): Long
}
