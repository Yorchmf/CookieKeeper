package eu.cookiekeeper.scan

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Delivery state of a queue job (`jobs.status`), distinct from the scan's own lifecycle. */
enum class JobStatus(
    val dbValue: String,
) {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    ;

    companion object {
        fun fromDbValue(value: String): JobStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown job status: $value")
    }
}

@Converter
class JobStatusConverter : AttributeConverter<JobStatus, String> {
    override fun convertToDatabaseColumn(attribute: JobStatus): String = attribute.dbValue

    override fun convertToEntityAttribute(dbData: String): JobStatus = JobStatus.fromDbValue(dbData)
}

/**
 * A row in the Postgres-backed work queue (`jobs`, ADR-4). Generic by design — [type] selects
 * the handler and [payload] carries its arguments (for scans, `{"scanId": "<uuid>"}`) — but the
 * only producer/consumer today is the scanner.
 *
 * Claiming is the one operation that is NOT a plain JPA save: [JobRepository.claimNextId] takes a
 * row lock with `FOR UPDATE SKIP LOCKED` so concurrent workers never grab the same job. Every
 * other transition (running -> done/failed/retry) is an immutable `copy(...)` + save within the
 * claiming worker's transaction, so no second worker can touch a row this one has claimed.
 */
@Entity
@Table(name = "jobs")
data class JobEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "type", nullable = false)
    val type: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_jsonb", nullable = false)
    val payload: Map<String, String>,
    @Convert(converter = JobStatusConverter::class)
    @Column(nullable = false)
    val status: JobStatus = JobStatus.PENDING,
    @Column(nullable = false)
    val attempts: Int = 0,
    @Column(name = "max_attempts", nullable = false)
    val maxAttempts: Int,
    // Claim ordering weight (higher = served first). Frozen from the site owner's entitlement at
    // enqueue time (see [ScanQueue.enqueue]); default 0 is the normal tier.
    @Column(name = "priority", nullable = false)
    val priority: Int = 0,
    @Column(name = "available_at", nullable = false)
    val availableAt: Instant,
    @Column(name = "locked_until")
    val lockedUntil: Instant? = null,
    @Column(name = "last_error")
    val lastError: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)

interface JobRepository : JpaRepository<JobEntity, UUID> {
    /**
     * Atomically claim the id of the oldest due job of [type], or null if none is ready. A job is
     * "due" when it is `pending` and past its `available_at`, OR `running` but its visibility lock
     * (`locked_until`) has expired — the latter redelivers a job whose worker crashed mid-run.
     *
     * `FOR UPDATE SKIP LOCKED` row-locks the selected job and makes concurrent callers skip past
     * it to the next candidate, so N workers claim N distinct jobs with no contention. The lock is
     * held for the rest of the caller's transaction; the caller must, in that same transaction,
     * flip the row to `running` with a fresh `locked_until` (see [ScanQueue.claimNext]) so the
     * claim survives past commit. Native because JPQL cannot express SKIP LOCKED.
     *
     * `ORDER BY priority DESC, available_at`: higher-priority jobs (Business-plan sites) are served
     * first, then oldest-due within a tier — matched by `idx_jobs_claim (type, priority DESC,
     * available_at)` so the LIMIT 1 claim is an index-order walk that stops at the first live row.
     */
    @Query(
        value =
            "SELECT id FROM jobs " +
                "WHERE type = :type AND (" +
                "(status = 'pending' AND available_at <= now()) OR " +
                "(status = 'running' AND (locked_until IS NULL OR locked_until < now()))" +
                ") ORDER BY priority DESC, available_at " +
                "FOR UPDATE SKIP LOCKED LIMIT 1",
        nativeQuery = true,
    )
    fun claimNextId(
        @Param("type") type: String,
    ): UUID?
}
