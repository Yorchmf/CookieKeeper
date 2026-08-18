package eu.cookiekeeper.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
data class UserEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val email: String,
    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,
    // Optional account-holder display name (V23, <=120 chars). Null when never set; callers fall back to
    // the email. Cleared to null when the user submits a blank name.
    @Column(name = "name")
    val name: String? = null,
    @Column(nullable = false)
    val locale: String = "en",
    // New email awaiting confirmation in the "verify the new address first" change flow (V24). Null in the
    // steady state. The account email swaps to this value only when the mailed email_change token is
    // confirmed (see [eu.cookiekeeper.auth.AuthService.confirmEmailChange]); it is cleared to null on that swap
    // and on Art. 17 erasure. Deliberately NOT unique — uniqueness is enforced against [email] at swap time.
    @Column(name = "pending_email")
    val pendingEmail: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "verified_at")
    val verifiedAt: Instant? = null,
    // Consecutive failed logins since the last success/lock, and the instant the account is locked
    // until (null = not locked). Maintained by [eu.cookiekeeper.auth.LoginAttemptService]; see V14.
    @Column(name = "failed_login_attempts", nullable = false)
    val failedLoginAttempts: Int = 0,
    @Column(name = "locked_until")
    val lockedUntil: Instant? = null,
    // When the "your trial ends soon" reminder was sent (V19). Null = not yet reminded. The no-card trial
    // is derived from [createdAt], not stored, so there is no subscription row to hang send-once state
    // off; this column is it. Claimed atomically by [UserRepository.markTrialEndingEmailSent].
    @Column(name = "trial_ending_email_sent_at")
    val trialEndingEmailSentAt: Instant? = null,
    // Non-null once the account was erased under Art. 17 (ADR-20, V22). The row is then a tombstone:
    // no personal data left, kept only because the surviving consent-bearing sites reference it. Login
    // cannot reach a tombstone (the email is destroyed), but an access JWT minted just before the
    // erasure stays verifiable for its remaining TTL — so every path that loads a user BY ID and acts
    // on it must reject a tombstone. [isErased] is that check.
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
) {
    /** True for an Art. 17 tombstone: authenticate/act-as paths must treat it as a non-existent user. */
    val isErased: Boolean get() = deletedAt != null
}

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?

    /**
     * True when [id] names an Art. 17 tombstone. A primary-key existence check rather than a full entity
     * load because [eu.cookiekeeper.common.ErasedAccountFilter] runs it on every authenticated request — it
     * touches only the `users` PK index and materializes no row.
     */
    fun existsByIdAndDeletedAtIsNotNull(id: UUID): Boolean

    /**
     * Transaction-scoped Postgres advisory lock keyed on a user, taken at the top of the failed-login
     * bump so concurrent guesses for one account serialize instead of racing the read-then-write on the
     * attempt counter (a lost update would let extra attempts slip past the lockout cap). Released at
     * commit/rollback; the wrapping `SELECT count(*)` just gives the native query a mappable result.
     * Mirrors [eu.cookiekeeper.site.SiteRepository.acquireUserSiteLock]. See
     * [eu.cookiekeeper.auth.LoginAttemptService.recordFailure].
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserLoginLock(
        @Param("key") key: Long,
    ): Long

    /**
     * Non-blocking run-wide advisory lock for [eu.cookiekeeper.billing.TrialEndingReminderJob]'s candidate
     * read: a second replica firing on the same cron exits immediately instead of duplicating the sweep.
     * Efficiency only — send-once correctness is [markTrialEndingEmailSent]'s compare-and-set, not this.
     * Mirrors [eu.cookiekeeper.scan.ScanRepository.tryAcquireAdvisoryXactLock].
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    fun tryAcquireAdvisoryXactLock(
        @Param("key") key: Long,
    ): Boolean

    /**
     * Accounts whose no-card trial ends inside the reminder lead window and who have not been reminded
     * yet. The trial is derived (`created_at + complyr.billing.trial-period`, see
     * [eu.cookiekeeper.billing.PlanResolver]), so the window is inverted by the caller into a `created_at`
     * range rather than expressed as date arithmetic here — that keeps the trial-period config out of SQL
     * and lets the query ride `idx_users_trial_reminder_pending` (V19).
     *
     * Unverified accounts are excluded: someone who never confirmed their email is not an account we
     * should be nudging about payment. So are accounts with a live subscription — [activeStatuses] is
     * passed in from [eu.cookiekeeper.billing.SubscriptionEntity.ACTIVE_STATUSES] rather than inlined, so the
     * definition of "active" lives in exactly one place. Art. 17 tombstones are excluded explicitly
     * (ADR-20): the erasure also clears `verifiedAt`, but relying on that to keep us from mailing an
     * `@erased.invalid` address would be an accident waiting for the next change to the verify rule.
     *
     * Ordered oldest-first (closest to trial end) and bounded by [pageable] so a backlog drains over
     * successive runs instead of arriving at the mail provider as one burst.
     */
    @Query(
        """
        SELECT u FROM UserEntity u
        WHERE u.deletedAt IS NULL
          AND u.verifiedAt IS NOT NULL
          AND u.trialEndingEmailSentAt IS NULL
          AND u.createdAt > :createdAtAfter
          AND u.createdAt <= :createdAtUpTo
          AND NOT EXISTS (
              SELECT 1 FROM SubscriptionEntity s
              WHERE s.userId = u.id AND s.status IN :activeStatuses
          )
        ORDER BY u.createdAt ASC
        """,
    )
    fun findTrialEndingCandidates(
        @Param("createdAtAfter") createdAtAfter: Instant,
        @Param("createdAtUpTo") createdAtUpTo: Instant,
        @Param("activeStatuses") activeStatuses: Collection<String>,
        pageable: Pageable,
    ): List<UserEntity>

    /**
     * Claim the trial-ending reminder for one account, returning 1 when this caller won and 0 when the
     * account was already claimed. A conditional UPDATE rather than a read-then-write is what makes the
     * reminder send-once without any lock: two replicas racing the same account both issue this statement,
     * Postgres serializes them on the row, and only the first sees `trial_ending_email_sent_at IS NULL`.
     * The caller publishes the email event only on a 1.
     *
     * Note the ordering this implies: the marker is committed BEFORE the mail is attempted, so a mail
     * provider outage loses that account's reminder rather than re-sending it daily. At-most-once is the
     * right failure mode for a nudge — the dashboard already shows the trial countdown.
     */
    @Modifying
    @Query(
        """
        UPDATE UserEntity u SET u.trialEndingEmailSentAt = :sentAt
        WHERE u.id = :userId AND u.trialEndingEmailSentAt IS NULL
        """,
    )
    fun markTrialEndingEmailSent(
        @Param("userId") userId: UUID,
        @Param("sentAt") sentAt: Instant,
    ): Int
}
