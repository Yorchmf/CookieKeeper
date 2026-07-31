package com.complyr.consent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Dedupe claim-check for consent ingestion (`consent_idempotency`). A row here means the
 * client idempotency key it carries has already been recorded, so a replayed widget retry
 * bearing the same key is skipped instead of writing a duplicate audit row. See V5 migration
 * for why this lives beside — not inside — the partitioned, append-only `consent_events`.
 *
 * Disposable bookkeeping, NOT audit evidence: rows may be pruned by a future retention job.
 * Only [eventKey] is mapped; `created_at` is a DB-defaulted column read only by that prune.
 */
@Entity
@Table(name = "consent_idempotency")
class ConsentIdempotencyEntity(
    @Id
    @Column(name = "event_key", nullable = false, updatable = false)
    val eventKey: UUID,
)

interface ConsentIdempotencyRepository : Repository<ConsentIdempotencyEntity, UUID> {
    /**
     * Atomically reserve [eventKey], returning 1 when this call inserted it and 0 when it was
     * already present (a replayed retry). `ON CONFLICT DO NOTHING` is the only viable conflict
     * action: the append-only trigger on the sibling table forbids UPDATE, and DO NOTHING gives
     * a lock-serialized winner under READ COMMITTED so concurrent retries can't both claim it.
     */
    @Modifying
    @Query(
        // Explicit conflict target (not a bare DO NOTHING) so this only ever swallows a duplicate
        // event_key — a future unique/exclusion constraint won't be silently masked here.
        value = "INSERT INTO consent_idempotency (event_key) VALUES (:eventKey) ON CONFLICT (event_key) DO NOTHING",
        nativeQuery = true,
    )
    fun claim(
        @Param("eventKey") eventKey: UUID,
    ): Int

    /**
     * Prune dedupe keys claimed before [cutoff], returning the number of rows removed. Native
     * because `created_at` is intentionally not mapped on the entity (it exists only for this
     * scan). This is disposable bookkeeping, not audit evidence, so DELETE is allowed here —
     * unlike the append-only sibling `consent_events`. Called only by the scheduled
     * [ConsentIdempotencyReaper]; keys must outlive a pending widget retry, nothing longer.
     */
    @Modifying
    @Query(
        value = "DELETE FROM consent_idempotency WHERE created_at < :cutoff",
        nativeQuery = true,
    )
    fun deleteClaimedBefore(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
