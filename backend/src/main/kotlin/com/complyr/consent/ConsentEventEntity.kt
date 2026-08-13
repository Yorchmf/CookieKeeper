package com.complyr.consent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * Immutable consent audit record (`consent_events`). APPEND-ONLY — CLAUDE.md constraint #3:
 * rows are never UPDATEd or DELETEd from application code; the scheduled retention job is the
 * only writer that removes rows. No raw IPs (see [com.complyr.common.IpHasher]) and no raw
 * user agents ([ua] is length-trimmed) ever reach this table.
 *
 * The table is monthly-RANGE-partitioned on `created_at` (see V3 migration). [eventId] is a
 * Hibernate-generated UUIDv7 (time-ordered → sequential index locality on the hot write path).
 * Because the id is generated rather than assigned, it is null until persist, so Spring Data
 * `save()` naturally routes to `persist()` (INSERT) with no SELECT-before-INSERT and no merge.
 * Kept out of the primary constructor so it stays out of `equals`/`hashCode`/`copy`.
 */
@Entity
@Table(name = "consent_events")
data class ConsentEventEntity(
    @Column(name = "site_id", nullable = false)
    val siteId: UUID,
    @Column(name = "visitor_id", nullable = false)
    val visitorId: UUID,
    @Column(nullable = false)
    val action: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories_jsonb", nullable = false)
    val categories: Map<String, Boolean>,
    @Column(name = "banner_version")
    val bannerVersion: Int? = null,
    @Column(name = "policy_version")
    val policyVersion: Int? = null,
    @Column(name = "lang")
    val lang: String? = null,
    @Column(name = "ip_hash")
    val ipHash: String? = null,
    @Column(name = "ua_trimmed")
    val ua: String? = null,
    // No default: the audit timestamp must come from the service's injected Clock
    // (ConsentService stamps clock.instant()), never an ambient wall clock.
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    var eventId: UUID? = null
}

/**
 * Write-side access to the consent audit log. Extends the minimal [Repository] marker (NOT
 * `JpaRepository`/`CrudRepository`) so no `delete`/`deleteAll`/bulk-update method is inherited —
 * the append-only invariant is enforced at the type level, not just by convention. Reads are
 * deliberately narrow and bounded: never an unpaginated `findAll()` across a multi-year,
 * monthly-partitioned table. [findByVisitorId] is the audit-correlation query backed by the
 * `idx_consent_events_visitor_id` index (V3). The dashboard's filterable, keyset-paginated log
 * comes from the [ConsentEventLogFragment] mix-in (still read-only — append-only stays intact).
 */
interface ConsentEventRepository :
    Repository<ConsentEventEntity, UUID>,
    ConsentEventLogFragment {
    fun save(event: ConsentEventEntity): ConsentEventEntity

    fun countBySiteId(siteId: UUID): Long

    /**
     * How many consent events the given sites recorded since [createdAt] — the read behind the trial
     * consent-usage meter ([com.complyr.billing.EntitlementService.summarize]). A COUNT is a read, so
     * the append-only invariant is untouched: this never gates ingestion (CLAUDE.md #3), it only tells
     * the dashboard how much of the trial allowance has been used. The `created_at >= :createdAt` bound
     * (the account's creation instant for a trialing account) lets Postgres prune to the trial's own
     * monthly partitions rather than scanning the whole multi-year table. Callers must pass a non-empty
     * [siteIds] — `site_id IN ()` is not valid SQL.
     */
    fun countBySiteIdInAndCreatedAtGreaterThanEqual(
        siteIds: Collection<UUID>,
        createdAt: Instant,
    ): Long

    fun findByVisitorId(visitorId: UUID): List<ConsentEventEntity>
}
