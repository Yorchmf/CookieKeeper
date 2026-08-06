package com.complyr.billing

import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.common.ComplyrProperties
import com.complyr.common.UnauthenticatedException
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [EntitlementService] — the billing-state resolver + site-cap guard. [PlanResolver] is
 * real (its own resolution order is covered by PlanResolverTest); the repositories are mocked so we
 * assert the userId → (createdAt, subscription) → [AccountEntitlement] wiring and the cap comparison.
 */
class EntitlementServiceTest {
    private val now = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val trialPeriod: Duration = Duration.ofDays(14)
    private val trialCap = 1_000L

    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            billing = ComplyrProperties.Billing(trialPeriod = trialPeriod, trialConsentEventCap = trialCap),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.complyr.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val siteRepository = mockk<SiteRepository>()
    private val service = EntitlementService(userRepository, subscriptionRepository, siteRepository, PlanResolver(properties, clock))

    private val userId = UUID.randomUUID()

    private fun stubUser(createdAt: Instant) {
        every { userRepository.findById(userId) } returns
            Optional.of(UserEntity(id = userId, email = "a@example.com", passwordHash = "hash", createdAt = createdAt))
    }

    private fun subscription(
        plan: Plan,
        status: String = "active",
    ) = SubscriptionEntity(
        userId = userId,
        stripeCustomerId = "cus_1",
        stripeSubId = "sub_1",
        plan = plan,
        status = status,
        periodEnd = now,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `resolve returns Subscribed for an active subscription even after the trial window`() {
        // A paid plan wins regardless of the trial clock (createdAt is well past the trial).
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO)

        val result = service.resolve(userId)

        assertIs<AccountEntitlement.Subscribed>(result)
        assertEquals(Plan.PRO, result.plan)
    }

    @Test
    fun `resolve returns Trial with the configured consent cap inside the trial window`() {
        stubUser(createdAt = now.minusSeconds(3600))
        every { subscriptionRepository.findByUserId(userId) } returns null

        val result = service.resolve(userId)

        assertIs<AccountEntitlement.Trial>(result)
        assertEquals(trialCap, result.entitlements.consentEventCap, "the trial carries the config-set ingestion cap")
    }

    @Test
    fun `resolve returns Expired once the trial lapses with no subscription`() {
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns null

        assertEquals(AccountEntitlement.Expired, service.resolve(userId))
    }

    @Test
    fun `resolve throws when the user row is gone`() {
        every { userRepository.findById(userId) } returns Optional.empty()

        assertThrows<UnauthenticatedException> { service.resolve(userId) }
    }

    @Test
    fun `summarize pairs the resolved entitlement with the active-site count`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO)
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 2

        val summary = service.summarize(userId)

        assertIs<AccountEntitlement.Subscribed>(summary.entitlement)
        assertEquals(2, summary.activeSites, "usage count is surfaced alongside the entitlement")
    }

    @Test
    fun `requireCanAddSite allows a create below the plan cap`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO) // cap 3
        every { siteRepository.acquireUserSiteLock(any()) } returns 1L
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 2

        service.requireCanAddSite(userId) // must not throw
    }

    @Test
    fun `requireCanAddSite rejects a create at the plan cap`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.STARTER) // cap 1
        every { siteRepository.acquireUserSiteLock(any()) } returns 1L
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 1

        assertThrows<SiteLimitReachedException> { service.requireCanAddSite(userId) }
    }

    @Test
    fun `requireCanAddSite takes the per-user advisory lock before reading the count`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO)
        every { siteRepository.acquireUserSiteLock(any()) } returns 1L
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 0

        service.requireCanAddSite(userId)

        // The lock is what serializes concurrent creates; assert the count is never read without it.
        verifyOrder {
            siteRepository.acquireUserSiteLock(any())
            siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE)
        }
    }

    @Test
    fun `requireOnDemandRescan allows a plan that includes it`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO)

        service.requireOnDemandRescan(userId) // must not throw
    }

    @Test
    fun `requireOnDemandRescan refuses Starter, which re-scans on its scheduled cadence instead`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.STARTER)

        assertThrows<OnDemandRescanNotEntitledException> { service.requireOnDemandRescan(userId) }
    }

    @Test
    fun `requireOnDemandRescan refuses a trial account`() {
        // The trial is Starter-shaped: it exists to prove the product, not to hand out the paid action.
        stubUser(createdAt = now.minusSeconds(3600))
        every { subscriptionRepository.findByUserId(userId) } returns null

        assertThrows<OnDemandRescanNotEntitledException> { service.requireOnDemandRescan(userId) }
    }

    @Test
    fun `requireOnDemandRescan freezes the action for an expired account`() {
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns null

        assertThrows<OnDemandRescanNotEntitledException> { service.requireOnDemandRescan(userId) }
    }

    @Test
    fun `requireCanAddSite freezes new sites for an expired account`() {
        // Expired entitlements cap sites at 0, so even a zero-site account can't add one.
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { siteRepository.acquireUserSiteLock(any()) } returns 1L
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 0

        assertThrows<SiteLimitReachedException> { service.requireCanAddSite(userId) }
    }
}
