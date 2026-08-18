package eu.cookiekeeper.notify

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The customer-facing view of an account's email opt-outs, decoupled from the persistence row so callers
 * never have to care whether a preference row exists yet. [DEFAULT] is what every account gets until it
 * changes something — opted in to both scan emails.
 */
data class NotificationPreferences(
    val scanComplete: Boolean,
    val scanChanges: Boolean,
) {
    companion object {
        /** All notifications on: the state of an account that has never touched the settings page. */
        val DEFAULT = NotificationPreferences(scanComplete = true, scanChanges = true)
    }
}

/**
 * Reads and writes an account's email notification preferences. A missing row is not an error — it is the
 * default-everything steady state ([NotificationPreferences.DEFAULT]) — so [get] never creates a row and
 * the common case (an account that never changed a default) stays a single indexed lookup that misses.
 *
 * The write path is a lazy upsert: the row is materialized only when the customer first changes something.
 * Scoped entirely by `userId`; there is no cross-account read here, and the caller
 * ([eu.cookiekeeper.account.AccountController]) only ever passes its own [eu.cookiekeeper.common.CurrentUser.id].
 */
@Service
class NotificationPreferenceService(
    private val repository: NotificationPreferenceRepository,
    private val clock: Clock,
) {
    /** The account's current preferences, or the all-on default when it has never changed one. */
    @Transactional(readOnly = true)
    fun get(userId: UUID): NotificationPreferences =
        repository.findByUserId(userId).map(::toPreferences).orElse(NotificationPreferences.DEFAULT)

    /**
     * Persists the account's preferences, creating the row on first change and updating it thereafter.
     * Returns the stored values so the caller can answer the request from the write without a re-read.
     */
    @Transactional
    fun update(
        userId: UUID,
        preferences: NotificationPreferences,
    ): NotificationPreferences {
        val now = clock.instant()
        val existing = repository.findByUserId(userId).orElse(null)
        val row =
            existing?.copy(
                scanComplete = preferences.scanComplete,
                scanChanges = preferences.scanChanges,
                updatedAt = now,
            ) ?: NotificationPreferenceEntity(
                userId = userId,
                scanComplete = preferences.scanComplete,
                scanChanges = preferences.scanChanges,
                createdAt = now,
                updatedAt = now,
            )
        return toPreferences(repository.save(row))
    }

    private fun toPreferences(entity: NotificationPreferenceEntity): NotificationPreferences =
        NotificationPreferences(scanComplete = entity.scanComplete, scanChanges = entity.scanChanges)
}
