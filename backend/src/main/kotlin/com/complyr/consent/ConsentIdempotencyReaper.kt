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
 *
 * Multi-instance safe: `@Scheduled` fires on every replica, so the prune first claims a
 * transaction-scoped Postgres advisory lock and no-ops if another instance already holds it.
 * That turns a daily N-way lock convoy on the same rows into a single winner per run. The lock
 * auto-releases when the transaction ends, so a crashed run never strands it.
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
     * `@Transactional` scopes both the leader-guard lock and the bulk delete to one transaction;
     * a failure rolls back cleanly (releasing the lock) and the next run retries the same window.
     *
     * The advisory lock must be acquired inside this transaction — hence the guard is the first
     * statement — so it stays held through the delete and is released only at commit/rollback.
     */
    @Scheduled(cron = "\${complyr.consent.idempotency-prune-cron:$DEFAULT_PRUNE_CRON}")
    @Transactional
    fun prune() {
        if (!consentIdempotencyRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
            log.debug("Skipping consent idempotency prune; another instance holds the lock")
            return
        }
        val cutoff = clock.instant().minus(properties.consent.idempotencyRetention)
        val deleted = consentIdempotencyRepository.deleteClaimedBefore(cutoff)
        if (deleted > 0) {
            log.info("Pruned {} consent idempotency key(s) claimed before {}", deleted, cutoff)
        }
    }

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes the
         * prune across instances. Keep distinct from any other `pg_advisory*` key the app takes.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 4_827_913_006L

        // 03:30 daily (server zone) — off the traffic peak; the window is far wider than the churn.
        private const val DEFAULT_PRUNE_CRON = "0 30 3 * * *"
    }
}
