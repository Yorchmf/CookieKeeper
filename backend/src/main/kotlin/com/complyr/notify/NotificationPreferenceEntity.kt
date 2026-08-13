package com.complyr.notify

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * A user's email notification opt-outs (`notification_preferences`, V25). One row per user, keyed by
 * `user_id` (the PK is the FK). The row is created lazily the first time the customer changes a default,
 * so the absence of a row is the steady state and means "all defaults" — [NotificationPreferences.DEFAULT].
 *
 * State updates are immutable `copy(...)` + save, matching [com.complyr.billing.SubscriptionEntity] and
 * the rest of the codebase. There is no domain logic on the entity; the send/skip decision lives in the
 * caller ([com.complyr.scan.ScanCompletionNotifier]) so this stays a plain persistence record.
 */
@Entity
@Table(name = "notification_preferences")
data class NotificationPreferenceEntity(
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    // The first scan of a newly added site finished (ScanTrigger.SITE_ADDED). Default true: the first
    // result is the product's first visible value and the nudge to embed the widget.
    @Column(name = "scan_complete", nullable = false)
    val scanComplete: Boolean = true,
    // A monitoring re-scan found new or changed trackers (ScanTrigger.SCHEDULED with a diff). Default
    // true: this difference is the paid "we watch your site" promise.
    @Column(name = "scan_changes", nullable = false)
    val scanChanges: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

interface NotificationPreferenceRepository : JpaRepository<NotificationPreferenceEntity, UUID> {
    fun findByUserId(userId: UUID): Optional<NotificationPreferenceEntity>
}
