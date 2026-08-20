package eu.cookiekeeper.site

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    /** A `_cookiekeeper.<domain>` TXT record carries the site key — zone control, the ACME DNS-01 bar. */
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
    // Customer's preference to hide the "Powered by CookieKeeper" credit. Only a WISH — the effective
    // suppression is this AND the plan's removeBranding entitlement (EntitlementService). Defaults
    // true so an upgrade removes the credit without a toggle; the entitlement floor keeps free-tier
    // sites honest. See V21.
    @Column(name = "hide_branding", nullable = false)
    val hideBranding: Boolean = true,
    // What the site's visitors are consenting TO, versioned (V27, BACKLOG #18). Served on the widget
    // config and stamped into the consent cookie at the moment of choice; a strictly higher version
    // re-prompts. Bumped only by [ConsentBasisService] when a consent-decidable category comes newly
    // into use — never by a banner edit. [consentBasisCategories] is null until the first completed
    // scan seeds it, which is what stops a deploy from re-prompting every existing site's visitors.
    @Column(name = "consent_basis_version", nullable = false)
    val consentBasisVersion: Int = 1,
    @Column(name = "consent_basis_categories")
    val consentBasisCategories: String? = null,
    @Column(name = "consent_basis_changed_at")
    val consentBasisChangedAt: Instant? = null,
    @Column(name = "consent_basis_added")
    val consentBasisAdded: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

/**
 * One row of [SiteRepository.findRescanCandidates]: a site the scheduled re-scan job may enqueue this
 * run, its owner (so the job batch-resolves the plan without an N+1 — see
 * [eu.cookiekeeper.billing.EntitlementService.resolveAll]), and the timestamp of its most recent scan
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

    /**
     * Every site the account owns, archived included — the Art. 20 export
     * ([eu.cookiekeeper.account.AccountExportService]) must hand back what we hold, not what the dashboard
     * currently lists.
     */
    fun findAllByUserId(userId: UUID): List<SiteEntity>

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

    /** Active-site count for the plan site-cap guard (see [eu.cookiekeeper.billing.EntitlementService]). */
    fun countByUserIdAndStatus(
        userId: UUID,
        status: SiteStatus,
    ): Long

    /**
     * Every site id owned by [userId], across all statuses. Backs the trial consent-usage counter
     * ([eu.cookiekeeper.billing.EntitlementService.summarize]): `consent_events` is keyed only by
     * `site_id`, so an account's usage is summed over its sites. Archived sites are included on
     * purpose — events they ingested during the trial still counted against the allowance.
     */
    @Query("SELECT s.id FROM SiteEntity s WHERE s.userId = :userId")
    fun findIdsByUserId(
        @Param("userId") userId: UUID,
    ): List<UUID>

    /**
     * Transaction-scoped Postgres advisory lock keyed on a user, taken at the top of the site-cap guard
     * so concurrent site creations for one account serialize instead of both reading `count < cap` and
     * each inserting (a check-then-act race that could let a plan exceed its `maxSites`). Released at
     * commit/rollback; the wrapping `SELECT count(*)` just gives the native query a mappable result.
     * Mirrors [eu.cookiekeeper.policy.PolicyEntity]'s per-site lock. See
     * [eu.cookiekeeper.billing.EntitlementService.requireCanAddSite].
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserSiteLock(
        @Param("key") key: Long,
    ): Long

    /**
     * Record a site's FIRST observed consent basis without touching the version — the seeding half of
     * [ConsentBasisService]. Guarded on `IS NULL` so it is idempotent and so it can never overwrite a
     * basis a later scan has already grown: an existing site's visitors must not be re-prompted just
     * because we started tracking this.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SiteEntity s SET s.consentBasisCategories = :categories
        WHERE s.id = :siteId AND s.consentBasisCategories IS NULL
        """,
    )
    fun seedConsentBasis(
        @Param("siteId") siteId: UUID,
        @Param("categories") categories: String,
    ): Int

    /**
     * Bump a site's consent basis because a decidable category came newly into use, which re-prompts
     * its visitors. Compare-and-set on the basis the caller read ([expectedCategories]) so two scans
     * completing at once can only bump once — the loser reads the winner's set on its next run and
     * finds nothing new. Returns the number of rows updated: 0 means someone else got there first.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SiteEntity s
        SET s.consentBasisVersion = s.consentBasisVersion + 1,
            s.consentBasisCategories = :categories,
            s.consentBasisAdded = :added,
            s.consentBasisChangedAt = :changedAt
        WHERE s.id = :siteId AND s.consentBasisCategories = :expectedCategories
        """,
    )
    fun bumpConsentBasis(
        @Param("siteId") siteId: UUID,
        @Param("categories") categories: String,
        @Param("added") added: String,
        @Param("changedAt") changedAt: Instant,
        @Param("expectedCategories") expectedCategories: String,
    ): Int

    /**
     * Active sites eligible for a scheduled re-scan ([eu.cookiekeeper.scan.ScheduledRescanJob]): no
     * queued/running scan in flight, and whose most recent scan is older than [cutoff] — or which have
     * never been scanned. Ordered oldest-first (never-scanned first) and capped at [batchSize] so a
     * backlog drains over successive nightly runs rather than hitting the single-Chromium worker as one
     * burst.
     *
     * [cutoff] is a COARSE pre-filter keyed on the SHORTEST plan cadence (7 days, asserted by a guard
     * test on [eu.cookiekeeper.billing.RescanFrequency]); the exact per-plan cadence is applied in Kotlin so
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
