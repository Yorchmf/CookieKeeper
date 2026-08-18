package eu.cookiekeeper.analytics

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Read/write access to the `banner_impressions` per-site, per-day counter (Track 4 Slice D, migration V26).
 *
 * An `EntityManager`-backed native-SQL repository rather than a Spring Data `Repository<Entity, ID>`: the
 * table's key is composite (site_id, day), which a mapped `@Entity` could only express through an
 * `@IdClass`/`@EmbeddedId` this counter does not otherwise need, and the hot-path write is an UPSERT
 * (`ON CONFLICT DO UPDATE`) that has no clean JPA-save equivalent. Mirrors [ConsentAnalyticsRepository]'s
 * constructor-injected `EntityManager` + native-SQL shape. Every native statement here requires an active
 * transaction: [increment] runs inside the `@Transactional` ingestion service, and the lock/prune pair runs
 * inside the reaper's per-batch `TransactionTemplate`.
 *
 * NOT audit evidence (unlike the append-only consent repositories): this is a disposable aggregate, so it is
 * UPSERTed and DELETE-pruned freely. It also stores zero personal data — a row is only (site_id, day, count).
 */
@Repository
class BannerImpressionRepository(
    private val entityManager: EntityManager,
) {
    /**
     * Record one banner impression for [siteId] on [day] (a UTC calendar day). Inserts `count = 1` on the
     * first beacon of the (site, day) and atomically increments the existing row on every later one. The
     * explicit conflict target `(site_id, day)` matches the primary key, so this only ever folds a genuine
     * same-(site, day) collision — never masks an unrelated future constraint. Must run in a transaction.
     */
    fun increment(
        siteId: UUID,
        day: LocalDate,
    ): Int =
        entityManager
            .createNativeQuery(
                "INSERT INTO banner_impressions (site_id, day, count) VALUES (:siteId, :day, 1) " +
                    "ON CONFLICT (site_id, day) DO UPDATE SET count = banner_impressions.count + 1",
            ).setParameter("siteId", siteId)
            .setParameter("day", day)
            .executeUpdate()

    /**
     * Total impressions for [siteId] over the window, as the denominator for the site's interaction rate.
     *
     * The analytics layer resolves a half-open instant range `[from, to)`; this counter is bucketed to whole
     * UTC days, so the query honors that half-open range at day granularity: `day >= from::date AND
     * day < to` (compared against the `to` timestamp, so a day counts iff its 00:00Z start falls before
     * `to`). That keeps the denominator consistent with the consent numerator's exclusive `created_at < to`
     * — a sub-day `to = now` still counts today (00:00Z < now), while an exact-midnight `to` correctly
     * excludes that whole day, where the old inclusive `<= to::date` over-counted it.
     *
     * One residual approximation is inherent to day-grained storage: `from`'s partial day is counted whole
     * (a day cannot be split), so two ADJACENT windows share the single calendar day at their handoff
     * (`prior.to == current.from`) — that day lands in both denominators. Bounded to one day out of the
     * window, on a rate denominator that is an indicator not audit evidence, so it is accepted rather than
     * fixed (splitting it would only trade it for a symmetric `from`-edge undercount).
     */
    fun impressionCounts(
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): Long =
        scalar(
            "SELECT coalesce(sum(count), 0) FROM banner_impressions " +
                "WHERE site_id = :siteId AND day >= (:from AT TIME ZONE 'UTC')::date AND day < (:to AT TIME ZONE 'UTC')",
            mapOf("siteId" to siteId, "from" to from, "to" to to),
        )

    /**
     * Total impressions across MANY sites over the window — the denominator for the cross-site roll-up's
     * interaction rate ([AccountAnalyticsService]). Same day-bucketing as [impressionCounts]. [siteIds] must
     * not be empty (`site_id IN ()` is invalid SQL); the caller returns early for an account with no active
     * sites, and the guard here is defence in depth.
     */
    fun accountImpressionCounts(
        siteIds: Collection<UUID>,
        from: Instant,
        to: Instant,
    ): Long {
        if (siteIds.isEmpty()) return 0L
        return scalar(
            "SELECT coalesce(sum(count), 0) FROM banner_impressions " +
                "WHERE site_id IN (:siteIds) AND day >= (:from AT TIME ZONE 'UTC')::date AND day < (:to AT TIME ZONE 'UTC')",
            mapOf("siteIds" to siteIds, "from" to from, "to" to to),
        )
    }

    /**
     * Try to take the transaction-scoped advisory lock [key], true only for the caller that acquired it —
     * leader-guards the scheduled prune across replicas. Held for the rest of the current transaction and
     * released at commit/rollback, so it must be called from within one (the reaper's per-batch
     * `TransactionTemplate`). Mirrors [eu.cookiekeeper.consent.ConsentIdempotencyRepository.tryAcquireAdvisoryXactLock].
     */
    fun tryAcquireAdvisoryXactLock(key: Long): Boolean =
        entityManager
            .createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
            .setParameter("key", key)
            .singleResult as Boolean

    /**
     * Delete up to [batchSize] counter rows for days strictly before [cutoffDay], oldest first, returning the
     * number removed. The reaper loops this one-transaction-per-batch so a backlog drains in bounded chunks
     * rather than one long DELETE. Native because `ctid` is not JPA-mapped; the inner scan walks
     * `idx_banner_impressions_day`. Allowed to DELETE (unlike the append-only consent log) because this is a
     * disposable aggregate — see the V26 migration.
     */
    fun deleteBatchOlderThan(
        cutoffDay: LocalDate,
        batchSize: Int,
    ): Int =
        entityManager
            .createNativeQuery(
                "DELETE FROM banner_impressions WHERE ctid IN " +
                    "(SELECT ctid FROM banner_impressions WHERE day < :cutoffDay ORDER BY day LIMIT :batchSize)",
            ).setParameter("cutoffDay", cutoffDay)
            .setParameter("batchSize", batchSize)
            .executeUpdate()

    private fun scalar(
        sql: String,
        params: Map<String, Any>,
    ): Long =
        (
            entityManager
                .createNativeQuery(sql)
                .also { query -> params.forEach { (name, value) -> query.setParameter(name, value) } }
                .singleResult as Number
        ).toLong()
}
