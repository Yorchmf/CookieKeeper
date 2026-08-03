package com.complyr.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
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
    @Column(nullable = false)
    val locale: String = "en",
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "verified_at")
    val verifiedAt: Instant? = null,
    // Consecutive failed logins since the last success/lock, and the instant the account is locked
    // until (null = not locked). Maintained by [com.complyr.auth.LoginAttemptService]; see V14.
    @Column(name = "failed_login_attempts", nullable = false)
    val failedLoginAttempts: Int = 0,
    @Column(name = "locked_until")
    val lockedUntil: Instant? = null,
)

interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?

    /**
     * Transaction-scoped Postgres advisory lock keyed on a user, taken at the top of the failed-login
     * bump so concurrent guesses for one account serialize instead of racing the read-then-write on the
     * attempt counter (a lost update would let extra attempts slip past the lockout cap). Released at
     * commit/rollback; the wrapping `SELECT count(*)` just gives the native query a mappable result.
     * Mirrors [com.complyr.site.SiteRepository.acquireUserSiteLock]. See
     * [com.complyr.auth.LoginAttemptService.recordFailure].
     */
    @Query(
        value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserLoginLock(
        @Param("key") key: Long,
    ): Long
}
