package com.complyr.consent

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Prunes expired dedupe keys from `consent_idempotency`. The table gains one row per consent
 * event on a public, unauthenticated endpoint, so without this it grows unbounded. Keys only
 * need to survive a pending widget retry (the localStorage replay queue drains within days), so
 * anything older than [ComplyrProperties.Consent.idempotencyRetention] is safe to delete — a key
 * whose event is long since recorded can never be replayed to a duplicate.
 *
 * DELETE-pruned rather than DROP PARTITION (the table can't be partitioned on `created_at`
 * without forcing it into the dedupe key), so the window is kept short to bound table bloat —
 * see the V5 migration's bloat note.
 */
@Component
class ConsentIdempotencyReaper(
    private val consentIdempotencyRepository: ConsentIdempotencyRepository,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConsentIdempotencyReaper::class.java)

    /**
     * Deletes keys claimed before `now - retention`. Cron-scheduled off-peak; overridable via
     * `complyr.consent.idempotency-prune-cron` (defaulted here so no yml entry is required).
     * `@Transactional` scopes the bulk delete to one transaction; a failure rolls back cleanly
     * and the next run simply retries the same window.
     */
    @Scheduled(cron = "\${complyr.consent.idempotency-prune-cron:$DEFAULT_PRUNE_CRON}")
    @Transactional
    fun prune() {
        val cutoff = clock.instant().minus(properties.consent.idempotencyRetention)
        val deleted = consentIdempotencyRepository.deleteClaimedBefore(cutoff)
        if (deleted > 0) {
            log.info("Pruned {} consent idempotency key(s) claimed before {}", deleted, cutoff)
        }
    }

    private companion object {
        // 03:30 daily (server zone) — off the traffic peak; the window is far wider than the churn.
        const val DEFAULT_PRUNE_CRON = "0 30 3 * * *"
    }
}
