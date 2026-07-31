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
     * Try to take the transaction-scoped advisory lock [key], returning true only to the caller
     * that acquired it. Used to leader-guard the scheduled prune across backend replicas: a losing
     * caller skips its run. The lock is held for the rest of the current transaction and released
     * automatically at commit or rollback — so it must be called from within a transaction (the
     * reaper runs each prune batch inside its own `TransactionTemplate`), never on its own.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    fun tryAcquireAdvisoryXactLock(
        @Param("key") key: Long,
    ): Boolean

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
     * Delete up to [batchSize] dedupe keys claimed before [cutoff], returning the number removed.
     * The reaper calls this in a loop (one transaction per batch) so a large backlog drains in
     * bounded chunks instead of one long DELETE — see [ConsentIdempotencyReaper].
     *
     * The inner `SELECT ctid ... ORDER BY created_at LIMIT` walks the `created_at` index to pick
     * the oldest [batchSize] rows and deletes them by physical row id, which is why this must be
     * native (`ctid` and `created_at` are not JPA-mapped). No `SKIP LOCKED` is needed: the reaper
     * holds a per-batch advisory lock, so no two batches ever target overlapping rows concurrently.
     * Disposable bookkeeping, not audit evidence, so DELETE is allowed here — unlike the
     * append-only sibling `consent_events`.
     */
    @Modifying
    @Query(
        value =
            "DELETE FROM consent_idempotency WHERE ctid IN " +
                "(SELECT ctid FROM consent_idempotency WHERE created_at < :cutoff " +
                "ORDER BY created_at LIMIT :batchSize)",
        nativeQuery = true,
    )
    fun deleteBatchClaimedBefore(
        @Param("cutoff") cutoff: Instant,
        @Param("batchSize") batchSize: Int,
    ): Int
}
