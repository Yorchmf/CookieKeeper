package eu.cookiekeeper.analytics

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A day's consent decision counts, one row per (day, action) the trend query returns. */
data class DailyActionCount(
    val day: LocalDate,
    val action: String,
    val count: Long,
)

/** Per-category opt-in aggregate over the window: [optIns] true out of [decisions] events carrying the key. */
data class CategoryOptInCount(
    val category: String,
    val optIns: Long,
    val decisions: Long,
)

data class LanguageCountRow(
    val lang: String,
    val count: Long,
)

/** Decision count for one action across a set of sites — the account-level roll-up. */
data class ActionCountRow(
    val action: String,
    val count: Long,
)

/**
 * Read-only aggregation over the append-only [consent_events][eu.cookiekeeper.consent.ConsentEventEntity] table for
 * the dashboard analytics. Kept separate from the consent repositories (which the app deliberately keeps
 * write-append-only at the type level): this only ever reads, and expresses the grouping in native SQL where
 * `date_trunc`, `FILTER`, and `jsonb_each_text` are far clearer than Criteria. Every query is bounded by
 * `site_id` + the half-open `[from, to)` window so it prunes to the relevant monthly partitions rather than
 * scanning the whole multi-year table. `EntityManager` is constructor-injected, mirroring
 * [eu.cookiekeeper.consent.ConsentEventLogFragmentImpl].
 */
@Repository
class ConsentAnalyticsRepository(
    private val entityManager: EntityManager,
) {
    /** Daily decision counts by action within `[from, to)`, ascending by day. Days with no events are absent. */
    fun dailyActionCounts(
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): List<DailyActionCount> {
        val sql =
            """
            SELECT (date_trunc('day', created_at AT TIME ZONE 'UTC'))::date AS day, action, count(*) AS cnt
            FROM consent_events
            WHERE site_id = :siteId AND created_at >= :from AND created_at < :to
            GROUP BY day, action
            ORDER BY day ASC
            """.trimIndent()
        return rows(sql, siteId, from, to).map {
            DailyActionCount(
                day = it[0] as LocalDate,
                action = it[1] as String,
                count = (it[2] as Number).toLong(),
            )
        }
    }

    /**
     * Per-category opt-in aggregate: for every category key present on any event in the window, how many events
     * set it `true` ([optIns]) out of how many carried the key at all ([decisions]). `jsonb_each_text` expands
     * each event's category map to (key, "true"/"false") rows; the `FILTER` counts the trues.
     */
    fun categoryOptInCounts(
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): List<CategoryOptInCount> {
        val sql =
            """
            SELECT kv.key AS category,
                   count(*) FILTER (WHERE kv.value = 'true') AS optins,
                   count(*) AS decisions
            FROM consent_events ce, LATERAL jsonb_each_text(ce.categories_jsonb) AS kv
            WHERE ce.site_id = :siteId AND ce.created_at >= :from AND ce.created_at < :to
            GROUP BY kv.key
            """.trimIndent()
        return rows(sql, siteId, from, to).map {
            CategoryOptInCount(
                category = it[0] as String,
                optIns = (it[1] as Number).toLong(),
                decisions = (it[2] as Number).toLong(),
            )
        }
    }

    /** Event counts by visitor language within the window, most-frequent first. Null langs collapse to "". */
    fun languageCounts(
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): List<LanguageCountRow> {
        val sql =
            """
            SELECT coalesce(lang, '') AS lang, count(*) AS cnt
            FROM consent_events
            WHERE site_id = :siteId AND created_at >= :from AND created_at < :to
            GROUP BY lang
            ORDER BY cnt DESC, lang ASC
            """.trimIndent()
        return rows(sql, siteId, from, to).map {
            LanguageCountRow(lang = it[0] as String, count = (it[1] as Number).toLong())
        }
    }

    /**
     * Decision counts by action across MANY sites within `[from, to)` — the account-level headline for the
     * dashboard home ([OverviewService]). Same partition pruning as the per-site queries: the `created_at`
     * bounds still select the relevant monthly partitions, and `site_id IN (...)` narrows within them.
     *
     * [siteIds] must not be empty — `IN ()` is not valid SQL. The caller returns early for an account with
     * no sites (see [OverviewService]); the empty guard below is defence in depth for any future caller.
     */
    fun accountActionCounts(
        siteIds: Collection<UUID>,
        from: Instant,
        to: Instant,
    ): List<ActionCountRow> {
        if (siteIds.isEmpty()) return emptyList()
        val sql =
            """
            SELECT action, count(*) AS cnt
            FROM consent_events
            WHERE site_id IN (:siteIds) AND created_at >= :from AND created_at < :to
            GROUP BY action
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds, "from" to from, "to" to to)).map {
            ActionCountRow(action = it[0] as String, count = (it[1] as Number).toLong())
        }
    }

    /**
     * Daily decision counts by action across MANY sites within `[from, to)` — the multi-site trend behind the
     * cross-site analytics roll-up ([AccountAnalyticsService]). The account-level companion to the single-site
     * [dailyActionCounts], and the daily-bucketed sibling of [accountActionCounts]: same partition pruning, and
     * scoped by an explicit [siteIds] collection on purpose. Which sites belong to the account and are ACTIVE is
     * decided in the service (in Kotlin, against [eu.cookiekeeper.site.SiteStatus] via its converter) — never
     * re-encoded as a `status = '...'` literal in SQL, where the enum's DB value is easy to get wrong.
     *
     * [siteIds] must not be empty — `site_id IN ()` is not valid SQL; the caller returns early for an account
     * with no active sites (mirroring [OverviewService]).
     */
    fun accountDailyActionCounts(
        siteIds: Collection<UUID>,
        from: Instant,
        to: Instant,
    ): List<DailyActionCount> {
        if (siteIds.isEmpty()) return emptyList()
        val sql =
            """
            SELECT (date_trunc('day', created_at AT TIME ZONE 'UTC'))::date AS day, action, count(*) AS cnt
            FROM consent_events
            WHERE site_id IN (:siteIds) AND created_at >= :from AND created_at < :to
            GROUP BY day, action
            ORDER BY day ASC
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds, "from" to from, "to" to to)).map {
            DailyActionCount(
                day = it[0] as LocalDate,
                action = it[1] as String,
                count = (it[2] as Number).toLong(),
            )
        }
    }

    /**
     * Per-category opt-in aggregate across MANY sites within `[from, to)` — the account-level companion to
     * [categoryOptInCounts]. Category keys are shared across a customer's sites (the banner taxonomy), so the
     * tallies sum cleanly. [siteIds] must not be empty (see [accountDailyActionCounts]).
     */
    fun accountCategoryOptInCounts(
        siteIds: Collection<UUID>,
        from: Instant,
        to: Instant,
    ): List<CategoryOptInCount> {
        if (siteIds.isEmpty()) return emptyList()
        val sql =
            """
            SELECT kv.key AS category,
                   count(*) FILTER (WHERE kv.value = 'true') AS optins,
                   count(*) AS decisions
            FROM consent_events ce, LATERAL jsonb_each_text(ce.categories_jsonb) AS kv
            WHERE ce.site_id IN (:siteIds) AND ce.created_at >= :from AND ce.created_at < :to
            GROUP BY kv.key
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds, "from" to from, "to" to to)).map {
            CategoryOptInCount(
                category = it[0] as String,
                optIns = (it[1] as Number).toLong(),
                decisions = (it[2] as Number).toLong(),
            )
        }
    }

    /**
     * Event counts by visitor language across MANY sites within `[from, to)` — the account-level companion to
     * [languageCounts], most-frequent first. [siteIds] must not be empty (see [accountDailyActionCounts]).
     */
    fun accountLanguageCounts(
        siteIds: Collection<UUID>,
        from: Instant,
        to: Instant,
    ): List<LanguageCountRow> {
        if (siteIds.isEmpty()) return emptyList()
        val sql =
            """
            SELECT coalesce(lang, '') AS lang, count(*) AS cnt
            FROM consent_events
            WHERE site_id IN (:siteIds) AND created_at >= :from AND created_at < :to
            GROUP BY lang
            ORDER BY cnt DESC, lang ASC
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds, "from" to from, "to" to to)).map {
            LanguageCountRow(lang = it[0] as String, count = (it[1] as Number).toLong())
        }
    }

    private fun rows(
        sql: String,
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): List<Array<Any?>> = rows(sql, mapOf("siteId" to siteId, "from" to from, "to" to to))

    @Suppress("UNCHECKED_CAST")
    private fun rows(
        sql: String,
        params: Map<String, Any>,
    ): List<Array<Any?>> =
        entityManager
            .createNativeQuery(sql)
            .also { query -> params.forEach { (name, value) -> query.setParameter(name, value) } }
            .resultList as List<Array<Any?>>
}
