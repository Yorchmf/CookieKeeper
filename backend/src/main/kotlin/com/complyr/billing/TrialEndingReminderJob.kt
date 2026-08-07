package com.complyr.billing

import com.complyr.auth.UserRepository
import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.NestedRuntimeException
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Mails each trialling account once, a few days before its no-card trial lapses, so the lapse is never
 * a surprise. Without it the first signal a customer gets is a frozen dashboard — the worst possible
 * moment to ask someone for a card, and a support ticket every time.
 *
 * **The trial is derived, not stored.** An account is trialling while `now < users.created_at +
 * complyr.billing.trial-period` ([PlanResolver]); there is no trial row and no end-date column. So this
 * job inverts the window instead of scanning: an account is due when its `created_at` falls in
 * `(now - trialPeriod, now + leadTime - trialPeriod]`, which is exactly the set whose derived end lands
 * in `(now, now + leadTime]`. That keeps the trial-period config in Kotlin and lets the query ride the
 * partial `idx_users_trial_reminder_pending` (V19).
 *
 * **Send-once is a compare-and-set, not a lock.** [UserRepository.markTrialEndingEmailSent] only updates
 * a row whose marker is still null, and the email event is published only when that update reports 1.
 * Two replicas racing the same account therefore produce exactly one reminder, with no coordination —
 * the run-wide advisory lock below is a cost optimization, not the correctness guard.
 *
 * **Marker first, mail second.** The marker commits before [BillingEmailListener] even sees the event, so
 * a mail outage costs that account its reminder rather than re-sending daily until the provider recovers.
 * At-most-once is the right failure mode for a nudge: the dashboard already shows the trial countdown,
 * and a repeated "your trial is ending" is the kind of mail that gets a sender marked as spam.
 *
 * ## Transaction shape
 *
 * One short leader-guarded read, then one independent transaction per account — the same per-unit-of-work
 * commit [com.complyr.scan.ScheduledRescanJob] and [StripeWebhookReaper] use. A batch-wide transaction
 * would let a single failing account roll back every other account's marker, silently re-arming the
 * whole cohort for tomorrow (and re-sending to everyone already mailed).
 */
@Component
class TrialEndingReminderJob(
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: ComplyrProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(TrialEndingReminderJob::class.java)

    private val transactionTemplate = TransactionTemplate(transactionManager)

    /**
     * Remind every account whose trial ends inside the lead window. Cron-scheduled off-peak; overridable
     * via `complyr.billing.trial-reminder-cron` (defaulted here so no yml entry is required). [now] is
     * captured once so the due window and every marker in this run agree on a single instant.
     */
    @Scheduled(cron = "\${complyr.billing.trial-reminder-cron:$DEFAULT_TRIAL_REMINDER_CRON}")
    fun sendDueTrialReminders() {
        val now = clock.instant()
        val dueUserIds = transactionTemplate.execute { selectDueUsers(now) } ?: emptyList()
        if (dueUserIds.isEmpty()) return

        val reminded = dueUserIds.count { tryRemind(it, now) }
        if (reminded > 0) {
            log.info("Trial-ending reminder queued for {} account(s)", reminded)
        }
    }

    /**
     * The accounts due this run, inside the caller's read transaction. Claims the run-wide leader lock
     * (returns empty when another instance holds it) and inverts the trial window into the `created_at`
     * range the query understands. No markers are written here — the lock releases at this transaction's
     * commit and each claim then runs on its own (see [tryRemind]).
     */
    private fun selectDueUsers(now: Instant): List<UUID> {
        if (!userRepository.tryAcquireAdvisoryXactLock(ADVISORY_LOCK_KEY)) {
            log.debug("Skipping trial-ending reminders; another instance holds the lock")
            return emptyList()
        }
        val trialPeriod = properties.billing.trialPeriod
        return userRepository
            .findTrialEndingCandidates(
                // Exclusive lower bound: an account created exactly trialPeriod ago has already lapsed,
                // and reminding someone their trial ends "today, in the past" is worse than silence.
                createdAtAfter = now.minus(trialPeriod),
                createdAtUpTo = now.plus(properties.billing.trialReminderLeadTime).minus(trialPeriod),
                activeStatuses = SubscriptionEntity.ACTIVE_STATUSES,
                pageable = PageRequest.of(0, properties.billing.trialReminderBatchSize),
            ).map { it.id }
    }

    /**
     * Claim and queue one account's reminder in its own transaction, returning whether this run is the
     * one that sends it. A single account failing — a transient DB error, a lock timeout — is caught here
     * and dropped from this run rather than allowed to abort the batch; its marker was never written, so
     * tomorrow's run reconsiders it (and the lead window is days wide, so a lost night is not a lost
     * reminder). Only Spring's data/transaction failures are swallowed; a programming error propagates.
     */
    private fun tryRemind(
        userId: UUID,
        now: Instant,
    ): Boolean =
        try {
            transactionTemplate.execute { claimAndPublish(userId, now) } == true
        } catch (ex: NestedRuntimeException) {
            log.warn("Trial-ending reminder skipped user {}; will retry next run", userId, ex)
            false
        }

    /**
     * The compare-and-set for one account, inside its own transaction: claim the marker, and publish the
     * email event only if this caller won the claim. A 0 means another replica (or an earlier run whose
     * candidate list overlapped) already owns this reminder — not an error, just a no-op.
     */
    private fun claimAndPublish(
        userId: UUID,
        now: Instant,
    ): Boolean {
        if (userRepository.markTrialEndingEmailSent(userId, now) == 0) return false
        eventPublisher.publishEvent(TrialEnding(userId))
        return true
    }

    companion object {
        /**
         * Application-wide-unique advisory-lock key (arbitrary fixed constant) that serializes the
         * candidate read across replicas. Kept distinct from every other `pg_advisory*` key the app takes:
         * [com.complyr.scan.PublicScanReaper] 7_213_884_559, [StripeWebhookReaper] 8_431_907_662,
         * `ConsentIdempotencyReaper` 4_827_913_006, `ConsentEventPartitionReaper` 5_902_447_183,
         * [com.complyr.scan.ScheduledRescanJob] 6_538_192_074, `ConsentEventPartitionProvisioner`
         * 6_401_558_237.
         */
        internal const val ADVISORY_LOCK_KEY: Long = 3_174_650_982L

        // 04:40 daily (server zone): past the 03:30/03:45 reapers and the 04:20 re-scan sweep, so the
        // nightly jobs never contend. Overridable via complyr.billing.trial-reminder-cron.
        private const val DEFAULT_TRIAL_REMINDER_CRON = "0 40 4 * * *"
    }
}
