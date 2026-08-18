package eu.cookiekeeper.analytics

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** A site's most recent COMPLETED scan: which scan, and when the crawl finished. */
data class LatestScanRow(
    val siteId: UUID,
    val scanId: UUID,
    val scannedAt: Instant,
)

/** Cookie totals for one scan: everything it observed, and how many were insecure. */
data class ScanCookieTotals(
    val scanId: UUID,
    val total: Int,
    val insecure: Int,
)

/** A site's most recent PUBLISHED policy version. [publishedAt] is non-null — the query requires it. */
data class LatestPolicyRow(
    val siteId: UUID,
    val version: Int,
    val publishedAt: Instant,
)

/**
 * A site's highest banner-config version. Every site is seeded a published v1 on creation
 * ([eu.cookiekeeper.banner.BannerConfigService.createDefaultFor]) and edits append v2+, so a [version] above
 * the seed is the signal that the customer has customised their banner — the onboarding checklist reads it
 * that way. Includes drafts: a saved-but-unpublished edit still counts as the customer having engaged.
 */
data class BannerVersionRow(
    val siteId: UUID,
    val version: Int,
)

/**
 * Batch reads backing the account-level dashboard home ([OverviewService]). Every query takes the whole set
 * of the account's site ids and returns one row per site, because the alternative — looping the per-site
 * finders in [AnalyticsService] — is an N+1 on the page a customer hits most often.
 *
 * Native SQL for the same reason [ConsentAnalyticsRepository] uses it: Postgres `DISTINCT ON` expresses
 * "latest row per group" in one index-ordered pass, where the JPA equivalent is a correlated subquery per
 * site. Read-only; nothing here mutates.
 *
 * Callers MUST NOT pass an empty id collection — `IN ()` is not valid SQL. [OverviewService] returns early
 * for an account with no sites, which is also the single most common state on this page.
 */
@Repository
class OverviewRepository(
    private val entityManager: EntityManager,
) {
    /**
     * Each site's latest completed scan. `scannedAt` prefers `finished_at` and falls back to `created_at`
     * for historical rows that predate the finish stamp, mirroring [AnalyticsService.cookieAnalytics].
     * Ordering is by `created_at` (not `finished_at`) so it rides `idx_scans_site_id_created_at` (V7); the
     * two agree on ordering because a scan cannot finish before it was created.
     */
    fun latestCompletedScans(siteIds: Collection<UUID>): List<LatestScanRow> {
        val sql =
            """
            SELECT DISTINCT ON (site_id) site_id, id, coalesce(finished_at, created_at) AS scanned_at
            FROM scans
            WHERE site_id IN (:siteIds) AND status = 'done'
            ORDER BY site_id, created_at DESC
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds)).map {
            LatestScanRow(
                siteId = it[0] as UUID,
                scanId = it[1] as UUID,
                scannedAt = it[2] as Instant,
            )
        }
    }

    /**
     * Per-scan cookie totals. The `insecure` predicate mirrors [AnalyticsService.isInsecure] and
     * [eu.cookiekeeper.scan.ComplianceAnalyzer]: a CLASSIFIED, non-essential cookie carrying neither `Secure`
     * nor `HttpOnly` is sent in the clear and readable from page script. The essential category key is
     * bound as a parameter rather than inlined so it stays tied to
     * [eu.cookiekeeper.banner.ConsentCategory.NECESSARY] instead of drifting as a magic string.
     */
    fun cookieTotals(
        scanIds: Collection<UUID>,
        necessaryCategory: String,
    ): List<ScanCookieTotals> {
        val sql =
            """
            SELECT scan_id,
                   count(*) AS total,
                   count(*) FILTER (
                       WHERE is_known
                         AND category IS NOT NULL
                         AND category <> :necessary
                         AND NOT secure
                         AND NOT http_only
                   ) AS insecure
            FROM scan_cookies
            WHERE scan_id IN (:scanIds)
            GROUP BY scan_id
            """.trimIndent()
        return rows(sql, mapOf("scanIds" to scanIds, "necessary" to necessaryCategory)).map {
            ScanCookieTotals(
                scanId = it[0] as UUID,
                total = (it[1] as Number).toInt(),
                insecure = (it[2] as Number).toInt(),
            )
        }
    }

    /**
     * Each site's highest PUBLISHED policy version. Drafts (`published_at IS NULL`) are excluded because a
     * policy visitors cannot see does not discharge the obligation — a site whose only policy is a draft
     * must still read as "missing". Mirrors
     * [eu.cookiekeeper.policy.PolicyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc],
     * batched.
     */
    fun latestPublishedPolicies(siteIds: Collection<UUID>): List<LatestPolicyRow> {
        val sql =
            """
            SELECT DISTINCT ON (site_id) site_id, version, published_at
            FROM policies
            WHERE site_id IN (:siteIds) AND published_at IS NOT NULL
            ORDER BY site_id, version DESC
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds)).map {
            LatestPolicyRow(
                siteId = it[0] as UUID,
                version = (it[1] as Number).toInt(),
                publishedAt = it[2] as Instant,
            )
        }
    }

    /**
     * Each site's highest banner-config version — the max over all versions (published or draft), since any
     * version beyond the seeded v1 means the customer has edited the banner. One `GROUP BY` pass keyed on
     * the account's site ids, mirroring the batch shape of the other reads here.
     */
    fun maxBannerVersions(siteIds: Collection<UUID>): List<BannerVersionRow> {
        val sql =
            """
            SELECT site_id, max(version) AS version
            FROM banner_configs
            WHERE site_id IN (:siteIds)
            GROUP BY site_id
            """.trimIndent()
        return rows(sql, mapOf("siteIds" to siteIds)).map {
            BannerVersionRow(
                siteId = it[0] as UUID,
                version = (it[1] as Number).toInt(),
            )
        }
    }

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
