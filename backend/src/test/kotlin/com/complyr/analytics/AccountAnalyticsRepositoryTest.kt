package com.complyr.analytics

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.consent.ConsentEventEntity
import com.complyr.consent.ConsentEventRepository
import com.complyr.site.SiteEntity
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * [ConsentAnalyticsRepository.accountDailyActionCounts] — the daily multi-site consent trend behind the
 * cross-site analytics roll-up. Locks the SQL: per-UTC-day bucketing, the `site_id IN (...)` scope, and the
 * half-open `[from, to)` window. The method is scoped by an explicit site-id collection, so "only ACTIVE
 * sites" is the service's concern (proven by the caller) — here we prove that only the ids passed in are
 * counted. Full Testcontainers context because the query is native Postgres over the partitioned
 * `consent_events` table.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AccountAnalyticsRepositoryTest {
    @Autowired private lateinit var userRepository: UserRepository

    @Autowired private lateinit var siteRepository: SiteRepository

    @Autowired private lateinit var consentEventRepository: ConsentEventRepository

    @Autowired private lateinit var consentAnalyticsRepository: ConsentAnalyticsRepository

    @Test
    fun `accountDailyActionCounts aggregates decisions across the given sites, bucketed by UTC day`() {
        val userId = persistUser()
        val siteA = persistSite(userId)
        val siteB = persistSite(userId)

        // Aug 13: siteA 3x accept_all + 1x reject_all; siteB 2x accept_all + 1x custom
        repeat(3) { seedEvent(siteA, "accept_all", AUG13.plusSeconds(3_600L * it + 3_600)) }
        seedEvent(siteA, "reject_all", AUG13.plusSeconds(20_000))
        repeat(2) { seedEvent(siteB, "accept_all", AUG13.plusSeconds(1_800L * it + 1_800)) }
        seedEvent(siteB, "custom", AUG13.plusSeconds(9_000))
        // Aug 14: siteA 1x reject_all
        seedEvent(siteA, "reject_all", AUG14.plusSeconds(3_600))

        val result =
            consentAnalyticsRepository.accountDailyActionCounts(
                siteIds = listOf(siteA, siteB),
                from = AUG13,
                to = AUG14.plusSeconds(86_400),
            )

        val aug13 = result.filter { it.day == AUG13.atZone(ZoneOffset.UTC).toLocalDate() }
        val aug14 = result.filter { it.day == AUG14.atZone(ZoneOffset.UTC).toLocalDate() }
        // Aug 13: accept_all 3+2=5 (both sites merged), reject_all 1, custom 1
        assertEquals(5L, aug13.single { it.action == "accept_all" }.count, "Aug 13 accept_all across both sites")
        assertEquals(1L, aug13.single { it.action == "reject_all" }.count, "Aug 13 reject_all")
        assertEquals(1L, aug13.single { it.action == "custom" }.count, "Aug 13 custom")
        // Aug 14: reject_all 1 from siteA only
        assertEquals(1L, aug14.single { it.action == "reject_all" }.count, "Aug 14 reject_all")
    }

    @Test
    fun `accountDailyActionCounts counts only the site ids passed in`() {
        val userId = persistUser()
        val included = persistSite(userId)
        val excluded = persistSite(userId, status = SiteStatus.ARCHIVED)
        seedEvent(included, "accept_all", AUG13.plusSeconds(3_600))
        seedEvent(excluded, "accept_all", AUG13.plusSeconds(3_600))

        val result =
            consentAnalyticsRepository.accountDailyActionCounts(
                siteIds = listOf(included),
                from = AUG13,
                to = AUG14,
            )

        assertEquals(1, result.size, "only the one passed-in site contributes a row")
        assertEquals(1L, result.single { it.action == "accept_all" }.count)
    }

    @Test
    fun `accountDailyActionCounts returns empty when no events fall in the window`() {
        val userId = persistUser()
        val site = persistSite(userId)
        seedEvent(site, "accept_all", AUG13.plusSeconds(3_600))

        val result =
            consentAnalyticsRepository.accountDailyActionCounts(
                siteIds = listOf(site),
                // Window starts after the only event.
                from = AUG14,
                to = AUG14.plusSeconds(86_400),
            )

        assertEquals(0, result.size, "an event outside the window is not counted")
    }

    private fun persistUser(): UUID =
        userRepository
            .save(UserEntity(email = "acct-${UUID.randomUUID()}@example.eu", passwordHash = "x"))
            .id

    private fun persistSite(
        userId: UUID,
        status: SiteStatus = SiteStatus.ACTIVE,
    ): UUID =
        siteRepository
            .save(
                SiteEntity(
                    userId = userId,
                    domain = "d-${UUID.randomUUID().toString().take(8)}.example.eu",
                    siteKey = "sk_${UUID.randomUUID()}",
                    status = status,
                ),
            ).id

    private fun seedEvent(
        siteId: UUID,
        action: String,
        at: Instant,
    ) {
        consentEventRepository.save(
            ConsentEventEntity(
                siteId = siteId,
                visitorId = UUID.randomUUID(),
                action = action,
                categories = mapOf("statistics" to (action == "accept_all")),
                lang = "en",
                ipHash = "hash-${UUID.randomUUID()}",
                ua = "test-agent",
                createdAt = at,
            ),
        )
    }

    private companion object {
        val AUG13: Instant = Instant.parse("2026-08-13T00:00:00Z")
        val AUG14: Instant = Instant.parse("2026-08-14T00:00:00Z")
    }
}
