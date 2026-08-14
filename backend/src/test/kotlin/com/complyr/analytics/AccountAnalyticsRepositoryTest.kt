package com.complyr.analytics

import com.complyr.analytics.dto.ActionBreakdown
import com.complyr.analytics.dto.DailyActionCount
import com.complyr.consent.ConsentEventEntity
import com.complyr.site.SiteEntity
import com.complyr.site.SiteStatus
import com.complyr.user.UserEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals

@DataJpaTest
@ActiveProfiles("test")
class AccountAnalyticsRepositoryTest {
    @Autowired
    private lateinit var userRepository: com.complyr.user.UserRepository

    @Autowired
    private lateinit var siteRepository: com.complyr.site.SiteRepository

    @Autowired
    private lateinit var consentRepository: com.complyr.consent.ConsentEventRepository

    @Autowired
    private lateinit var consentAnalyticsRepository: ConsentAnalyticsRepository

    @Test
    fun `dailyActionCountsMultiSite aggregates consent events across all active sites for a user`() {
        // Arrange: user with 3 sites (2 ACTIVE, 1 ARCHIVED)
        val userId = UUID.randomUUID()
        val user = UserEntity(
            id = userId,
            email = "test@example.eu",
            passwordHash = "hash",
            emailVerified = true,
        )
        userRepository.save(user)

        val site1 = SiteEntity(
            id = UUID.randomUUID(),
            userId = userId,
            domain = "site1.example.eu",
            status = SiteStatus.ACTIVE,
        )
        val site2 = SiteEntity(
            id = UUID.randomUUID(),
            userId = userId,
            domain = "site2.example.eu",
            status = SiteStatus.ACTIVE,
        )
        val site3Archived = SiteEntity(
            id = UUID.randomUUID(),
            userId = userId,
            domain = "site3.example.eu",
            status = SiteStatus.ARCHIVED,
        )
        siteRepository.saveAll(listOf(site1, site2, site3Archived))

        // Act: insert consent events
        // Site 1: Aug 13 (3x ACCEPT_ALL, 1x REJECT_ALL)
        val aug13Start = Instant.parse("2026-08-13T00:00:00Z")
        consentRepository.saveAll(
            listOf(
                ConsentEventEntity(
                    siteId = site1.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(3600),
                ),
                ConsentEventEntity(
                    siteId = site1.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(7200),
                ),
                ConsentEventEntity(
                    siteId = site1.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(10800),
                ),
                ConsentEventEntity(
                    siteId = site1.id,
                    action = "REJECT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(14400),
                ),
            )
        )

        // Site 2: Aug 13 (2x ACCEPT_ALL, 1x CUSTOM)
        consentRepository.saveAll(
            listOf(
                ConsentEventEntity(
                    siteId = site2.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(1800),
                ),
                ConsentEventEntity(
                    siteId = site2.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(5400),
                ),
                ConsentEventEntity(
                    siteId = site2.id,
                    action = "CUSTOM",
                    categories = mapOf("necessary" to true, "marketing" to false),
                    createdAt = aug13Start.plusSeconds(9000),
                ),
            )
        )

        // Site 3 (ARCHIVED): Aug 13 (2x ACCEPT_ALL) — should NOT be aggregated
        consentRepository.saveAll(
            listOf(
                ConsentEventEntity(
                    siteId = site3Archived.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(2700),
                ),
                ConsentEventEntity(
                    siteId = site3Archived.id,
                    action = "ACCEPT_ALL",
                    categories = mapOf("necessary" to true),
                    createdAt = aug13Start.plusSeconds(6300),
                ),
            )
        )

        // Aug 14: Site 1 only (1x REJECT_ALL)
        val aug14Start = Instant.parse("2026-08-14T00:00:00Z")
        consentRepository.save(
            ConsentEventEntity(
                siteId = site1.id,
                action = "REJECT_ALL",
                categories = mapOf("necessary" to true),
                createdAt = aug14Start.plusSeconds(3600),
            )
        )

        // Act: fetch aggregated daily counts
        val result = consentAnalyticsRepository.dailyActionCountsMultiSite(
            userId = userId,
            from = aug13Start,
            to = aug14Start.plusSeconds(86400), // Inclusive of Aug 14
        )

        // Assert
        assertEquals(
            2,
            result.count(),
            "Should have 2 days of aggregated data"
        )

        val aug13Results = result.filter { it.day == aug13Start.atZone(ZoneOffset.UTC).toLocalDate() }
        val aug14Results = result.filter { it.day == aug14Start.atZone(ZoneOffset.UTC).toLocalDate() }

        // Aug 13: ACCEPT_ALL (3+2=5), REJECT_ALL (1), CUSTOM (1) — archived site NOT included
        assertEquals(3, aug13Results.count { it.action == "ACCEPT_ALL" && it.count == 5 }, "Aug 13 accept count")
        assertEquals(1, aug13Results.count { it.action == "REJECT_ALL" && it.count == 1 }, "Aug 13 reject count")
        assertEquals(1, aug13Results.count { it.action == "CUSTOM" && it.count == 1 }, "Aug 13 custom count")

        // Aug 14: REJECT_ALL (1) from site 1 only
        assertEquals(1, aug14Results.count { it.action == "REJECT_ALL" && it.count == 1 }, "Aug 14 reject count")
    }

    @Test
    fun `dailyActionCountsMultiSite returns empty when user has no active sites`() {
        val userId = UUID.randomUUID()
        val user = UserEntity(
            id = userId,
            email = "isolated@example.eu",
            passwordHash = "hash",
            emailVerified = true,
        )
        userRepository.save(user)

        val result = consentAnalyticsRepository.dailyActionCountsMultiSite(
            userId = userId,
            from = Instant.parse("2026-08-01T00:00:00Z"),
            to = Instant.parse("2026-08-31T23:59:59Z"),
        )

        assertEquals(0, result.count(), "Should return empty for user with no sites")
    }

    @Test
    fun `dailyActionCountsMultiSite returns empty when no events in window`() {
        val userId = UUID.randomUUID()
        val user = UserEntity(
            id = userId,
            email = "noevents@example.eu",
            passwordHash = "hash",
            emailVerified = true,
        )
        userRepository.save(user)

        val site = SiteEntity(
            id = UUID.randomUUID(),
            userId = userId,
            domain = "site.example.eu",
            status = SiteStatus.ACTIVE,
        )
        siteRepository.save(site)

        // No events inserted

        val result = consentAnalyticsRepository.dailyActionCountsMultiSite(
            userId = userId,
            from = Instant.parse("2026-08-01T00:00:00Z"),
            to = Instant.parse("2026-08-31T23:59:59Z"),
        )

        assertEquals(0, result.count(), "Should return empty when no events exist")
    }
}
