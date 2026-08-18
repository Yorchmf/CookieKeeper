package eu.cookiekeeper.billing

import jakarta.persistence.Column
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
 * A verbatim, idempotent record of one Stripe webhook (`stripe_events`, V12 + V13). Stripe delivers
 * at-least-once and retries, so the handler dedupes on [stripeEventId] (unique) before applying an
 * event. [payload] is the raw request body, stored only until the event is applied: the handler NULLs
 * it when it stamps [processedAt] (redact-on-process), so a processed row keeps no customer PII —
 * dedupe/audit need only the id, type, and timestamps (CLAUDE.md #4). [processedAt] is null until the
 * handler finishes applying the event, which lets a crashed mid-process event be reprocessed.
 */
@Entity
@Table(name = "stripe_events")
data class StripeEventEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),
    @Column(name = "stripe_event_id", nullable = false, updatable = false)
    val stripeEventId: String,
    @Column(name = "type", nullable = false, updatable = false)
    val type: String,
    @Column(name = "payload")
    val payload: String?,
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant,
    @Column(name = "processed_at")
    val processedAt: Instant?,
)

interface StripeEventRepository : JpaRepository<StripeEventEntity, UUID> {
    /** Look up a logged event by its Stripe id (post-[insertIfAbsent], to read [processedAt]). */
    fun findByStripeEventId(stripeEventId: String): StripeEventEntity?

    /**
     * Atomically log the event, claiming its [stripeEventId] exactly once. Returns 1 when this call
     * inserted the row and 0 when the id was already present (a Stripe re-delivery). `ON CONFLICT DO
     * NOTHING` on the explicit unique target is the only race-free option — an `exists?`-then-`save`
     * would TOCTOU under concurrent retries; the conflict path here is serialized by the row lock.
     */
    @Modifying
    @Query(
        value =
            "INSERT INTO stripe_events (id, stripe_event_id, type, payload, received_at) " +
                "VALUES (:id, :stripeEventId, :type, :payload, :receivedAt) " +
                "ON CONFLICT (stripe_event_id) DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("stripeEventId") stripeEventId: String,
        @Param("type") type: String,
        @Param("payload") payload: String,
        @Param("receivedAt") receivedAt: Instant,
    ): Int

    /**
     * Stamp [processedAt] and redact the raw [payload] in one write, but only while the event is
     * still unprocessed (`processed_at IS NULL`). Returns the rows changed (1 for the winner, 0 for a
     * concurrent duplicate that already stamped it). Nulling the body here is the redact-on-process
     * step: once applied, the event keeps no PII.
     */
    @Modifying
    @Query(
        value =
            "UPDATE stripe_events SET processed_at = :processedAt, payload = NULL " +
                "WHERE stripe_event_id = :stripeEventId AND processed_at IS NULL",
        nativeQuery = true,
    )
    fun markProcessedAndRedact(
        @Param("stripeEventId") stripeEventId: String,
        @Param("processedAt") processedAt: Instant,
    ): Int

    /**
     * Try to take the transaction-scoped advisory lock [key], returning true only to the caller that
     * acquired it. Leader-guards the scheduled retention reaper across replicas; must be called from
     * within a transaction (the reaper runs each batch in its own `TransactionTemplate`).
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    fun tryAcquireAdvisoryXactLock(
        @Param("key") key: Long,
    ): Boolean

    /**
     * Delete up to [batchSize] events received before [cutoff], returning the number removed. The
     * reaper calls this in a loop (one transaction per batch) so a backlog drains in bounded, vacuum-
     * friendly chunks. `stripe_events` is a disposable inbox, not append-only audit evidence (unlike
     * `consent_events`), so DELETE is the right tool. Native: `ctid` is not JPA-mapped.
     */
    @Modifying
    @Query(
        value =
            "DELETE FROM stripe_events WHERE ctid IN " +
                "(SELECT ctid FROM stripe_events WHERE received_at < :cutoff " +
                "ORDER BY received_at LIMIT :batchSize)",
        nativeQuery = true,
    )
    fun deleteBatchReceivedBefore(
        @Param("cutoff") cutoff: Instant,
        @Param("batchSize") batchSize: Int,
    ): Int
}
