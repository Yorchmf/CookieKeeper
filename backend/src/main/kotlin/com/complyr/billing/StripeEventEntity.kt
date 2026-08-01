package com.complyr.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A verbatim, idempotent record of one Stripe webhook (`stripe_events`, V12). Stripe delivers
 * at-least-once and retries, so the handler dedupes on [stripeEventId] (unique) before applying an
 * event. [payload] is the raw request body stored byte-for-byte so it can be re-read or
 * signature-re-verified later. [processedAt] is null until the handler finishes applying the event.
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
    @Column(name = "payload", nullable = false, updatable = false)
    val payload: String,
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: Instant,
    @Column(name = "processed_at")
    val processedAt: Instant?,
)

interface StripeEventRepository : JpaRepository<StripeEventEntity, UUID> {
    /** Idempotency guard: has this Stripe event id already been logged (and thus handled)? */
    fun existsByStripeEventId(stripeEventId: String): Boolean
}
