package eu.cookiekeeper.analytics

import eu.cookiekeeper.TestcontainersConfiguration
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Testcontainers coverage for [BannerImpressionRepository] (Track 4 Slice D) — the native-SQL, composite-key
 * counter. Locks the behaviours the analytics read and the reaper depend on: the UPSERT increments in place
 * on the (site, day) primary key, the window count buckets by whole UTC day honoring the half-open instant
 * range `[from, to)` (a `to` at a day's 00:00Z start excludes that whole day, matching the consent
 * numerator's `created_at < to`), the multi-site count scopes to the ids passed in (with the empty-set
 * guard), and the dated batch delete removes only rows strictly before the cutoff day. Transactional so the
 * native `executeUpdate` writes run in a transaction and each test rolls back in isolation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class BannerImpressionRepositoryTest {
    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var siteRepository: SiteRepository

    @Autowired private lateinit var repository: BannerImpressionRepository

    @Test
    fun `increment inserts on first beacon and folds later ones into the same site-day row`() {
        val site = persistSite()

        repository.increment(site, AUG13_DAY)
        repository.increment(site, AUG13_DAY)
        repository.increment(site, AUG13_DAY)
        // A different day for the same site is a distinct row, not folded in.
        repository.increment(site, AUG14_DAY)

        // Whole window spanning both days: 3 on Aug 13 + 1 on Aug 14. `to` = Aug 15 00:00Z, and the range is
        // half-open (`day < to`), so both days' 00:00Z starts fall before it.
        assertEquals(4L, repository.impressionCounts(site, AUG13_START, AUG14_START.plusSeconds(86_400)))
        // Narrowed to Aug 13: a `to` inside Aug 13 excludes Aug 14, whose 00:00Z start is not < to.
        assertEquals(3L, repository.impressionCounts(site, AUG13_START, AUG13_END))
    }

    @Test
    fun `impressionCounts to boundary is half-open — an exact-midnight to excludes that whole day`() {
        val site = persistSite()
        repository.increment(site, AUG13_DAY)
        repository.increment(site, AUG14_DAY)

        // `to` = Aug 14 00:00Z: half-open `day < to` counts only Aug 13, never Aug 14 — the same day the
        // consent numerator drops with `created_at < to`. The old inclusive `<= to::date` wrongly pulled in
        // all of Aug 14 here, inflating the denominator by a full day and deflating the interaction rate.
        assertEquals(1L, repository.impressionCounts(site, AUG13_START, AUG14_START))
        // A sub-day `to` (e.g. `to = now`) still counts its own day: Aug 14's 00:00Z start is < 01:00Z.
        assertEquals(2L, repository.impressionCounts(site, AUG13_START, AUG14_START.plusSeconds(3_600)))
    }

    @Test
    fun `impressionCounts buckets by whole UTC day and returns 0 for a site with no rows`() {
        val site = persistSite()
        repository.increment(site, AUG13_DAY)

        // A window that starts after Aug 13 sees nothing; the day-bucketing means the boundary is by date.
        assertEquals(0L, repository.impressionCounts(site, AUG14_START, AUG14_START.plusSeconds(86_400)))
        // A site that never recorded an impression counts 0, not an error.
        assertEquals(0L, repository.impressionCounts(persistSite(), AUG13_START, AUG14_START.plusSeconds(86_400)))
    }

    @Test
    fun `accountImpressionCounts sums only the site ids passed in and guards the empty set`() {
        val included = persistSite()
        val excluded = persistSite()
        repository.increment(included, AUG13_DAY)
        repository.increment(included, AUG13_DAY)
        repository.increment(excluded, AUG13_DAY)

        val window = AUG14_START.plusSeconds(86_400)
        assertEquals(2L, repository.accountImpressionCounts(listOf(included), AUG13_START, window))
        assertEquals(3L, repository.accountImpressionCounts(listOf(included, excluded), AUG13_START, window))
        // Empty id set is a real state (account with no active sites) — 0, never invalid `IN ()` SQL.
        assertEquals(0L, repository.accountImpressionCounts(emptyList(), AUG13_START, window))
    }

    @Test
    fun `widgetActivity reports the last day and the today-slash-window counts`() {
        val site = persistSite()
        repeat(3) { repository.increment(site, AUG13_DAY) }
        repository.increment(site, AUG14_DAY)

        // Reading "today" as Aug 14 over a window opening Aug 13: 1 today, 4 across the window.
        val onAug14 = repository.widgetActivity(site, today = AUG14_DAY, windowStart = AUG13_DAY)
        assertEquals(AUG14_DAY, onAug14.lastDay)
        assertEquals(1L, onAug14.today)
        assertEquals(4L, onAug14.window)

        // windowStart is inclusive on the low end and the today filter is an exact day match: reading the
        // same rows as-of Aug 15 gives nothing "today" while the window still spans both days.
        val onAug15 = repository.widgetActivity(site, today = AUG14_DAY.plusDays(1), windowStart = AUG13_DAY)
        assertEquals(0L, onAug15.today)
        assertEquals(4L, onAug15.window)

        // A window that opens after the only rows: last day still reported (that is what makes the state
        // IDLE rather than NEVER_SEEN), but nothing falls inside it.
        val narrow = repository.widgetActivity(site, today = AUG14_DAY.plusDays(9), windowStart = AUG14_DAY.plusDays(3))
        assertEquals(AUG14_DAY, narrow.lastDay)
        assertEquals(0L, narrow.window)
    }

    @Test
    fun `widgetActivity returns a null last day for a site that never recorded an impression`() {
        val activity = repository.widgetActivity(persistSite(), today = AUG14_DAY, windowStart = AUG13_DAY)

        // The aggregate always yields one row, so "never seen" is a null max(day) — not an empty result.
        assertNull(activity.lastDay)
        assertEquals(0L, activity.today)
        assertEquals(0L, activity.window)
    }

    @Test
    fun `deleteBatchOlderThan removes only rows strictly before the cutoff day`() {
        val site = persistSite()
        repository.increment(site, AUG13_DAY)
        repository.increment(site, AUG14_DAY)

        // Cutoff = Aug 14: only the Aug 13 row is strictly older.
        val deleted = repository.deleteBatchOlderThan(cutoffDay = AUG14_DAY, batchSize = 100)

        assertEquals(1, deleted)
        // The Aug 14 row (== cutoff, not < cutoff) survives.
        assertEquals(1L, repository.impressionCounts(site, AUG13_START, AUG14_START.plusSeconds(86_400)))
    }

    private fun persistSite(): UUID {
        val userId =
            userRepository
                .save(UserEntity(email = "imp-${UUID.randomUUID()}@example.eu", passwordHash = "x"))
                .id
        return siteRepository
            .save(
                SiteEntity(
                    userId = userId,
                    domain = "d-${UUID.randomUUID().toString().take(8)}.example.eu",
                    siteKey = "sk_${UUID.randomUUID()}",
                ),
            ).id
    }

    private companion object {
        val AUG13_DAY: LocalDate = LocalDate.parse("2026-08-13")
        val AUG14_DAY: LocalDate = LocalDate.parse("2026-08-14")
        val AUG13_START: Instant = Instant.parse("2026-08-13T00:00:00Z")
        val AUG13_END: Instant = Instant.parse("2026-08-13T23:59:59Z")
        val AUG14_START: Instant = Instant.parse("2026-08-14T00:00:00Z")
    }
}
