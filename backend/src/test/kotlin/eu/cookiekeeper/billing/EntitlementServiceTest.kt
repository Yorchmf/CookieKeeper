package eu.cookiekeeper.billing

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.consent.ConsentEventRepository
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import kotlin.test.assertNull

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
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            billing = CookieKeeperProperties.Billing(trialPeriod = trialPeriod, trialConsentEventCap = trialCap),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val siteRepository = mockk<SiteRepository>()
    private val consentEventRepository = mockk<ConsentEventRepository>()
    private val service =
        EntitlementService(
            userRepository,
            subscriptionRepository,
            siteRepository,
            consentEventRepository,
            PlanResolver(properties, clock),
            clock,
        )

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
    fun `summarize pairs the resolved entitlement with the active-site count and no trial usage`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO)
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 2

        val summary = service.summarize(userId)

        assertIs<AccountEntitlement.Subscribed>(summary.entitlement)
        assertEquals(2, summary.activeSites, "usage count is surfaced alongside the entitlement")
        // The consent-usage meter is a trial-only signal; a subscribed account never triggers the count.
        assertNull(summary.consentEventsUsed, "consent usage is null off-trial")
        verify(exactly = 0) { siteRepository.findIdsByUserId(any()) }
        verify(exactly = 0) { consentEventRepository.countBySiteIdInAndCreatedAtGreaterThanEqual(any(), any()) }
    }

    @Test
    fun `summarize counts trial consent events across the account's sites since it was created`() {
        val createdAt = now.minusSeconds(3600) // inside the trial window
        stubUser(createdAt = createdAt)
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 1
        val siteIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { siteRepository.findIdsByUserId(userId) } returns siteIds
        // The count is floored at the account's creation instant so it prunes to the trial's partitions.
        every {
            consentEventRepository.countBySiteIdInAndCreatedAtGreaterThanEqual(siteIds, createdAt)
        } returns 250

        val summary = service.summarize(userId)

        assertIs<AccountEntitlement.Trial>(summary.entitlement)
        assertEquals(250, summary.consentEventsUsed, "trial usage is the consent count across the account's sites")
    }

    @Test
    fun `summarize reports zero trial usage when the account has no sites yet`() {
        stubUser(createdAt = now.minusSeconds(3600))
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { siteRepository.countByUserIdAndStatus(userId, SiteStatus.ACTIVE) } returns 0
        every { siteRepository.findIdsByUserId(userId) } returns emptyList()

        val summary = service.summarize(userId)

        assertEquals(0, summary.consentEventsUsed, "no sites means no usage — and no empty-IN query")
        // Guard the empty-IN case in Kotlin, never as `site_id IN ()`.
        verify(exactly = 0) { consentEventRepository.countBySiteIdInAndCreatedAtGreaterThanEqual(any(), any()) }
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

    @Test
    fun `consentRetentionFloor is 12 months back on the entry plan`() {
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.STARTER)

        // Calendar arithmetic at UTC, not a fixed 365-day Duration: Period is date-based.
        assertEquals(Instant.parse("2025-08-01T12:00:00Z"), service.consentRetentionFloor(userId))
    }

    @Test
    fun `consentRetentionFloor is 3 years back on the paid plans`() {
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.BUSINESS)

        assertEquals(Instant.parse("2023-08-01T12:00:00Z"), service.consentRetentionFloor(userId))
    }

    @Test
    fun `consentRetentionFloor holds at the entry window for an expired account`() {
        // Evidence keeps ageing out normally once the trial lapses — we never widen or collapse the window
        // to apply billing pressure (EXPIRED_ENTITLEMENTS pins retention at the 12-month tier).
        stubUser(createdAt = now.minus(trialPeriod).minusSeconds(1))
        every { subscriptionRepository.findByUserId(userId) } returns null

        assertEquals(Instant.parse("2025-08-01T12:00:00Z"), service.consentRetentionFloor(userId))
    }

    @Test
    fun `removeBrandingOrDefault reflects the plan flag`() {
        stubUser(createdAt = now)
        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO) // removeBranding = true
        assertEquals(true, service.removeBrandingOrDefault(userId))

        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.STARTER) // removeBranding = false
        assertEquals(false, service.removeBrandingOrDefault(userId))
    }

    @Test
    fun `removeBrandingOrDefault falls back to showing the attribution when billing cannot be read`() {
        // A since-deleted owner makes resolve throw UnauthenticatedException; the public hosted-policy and
        // widget-config reads must never 500 over a branding nicety, and must fail CLOSED (show it).
        every { userRepository.findById(userId) } returns Optional.empty()

        assertEquals(false, service.removeBrandingOrDefault(userId))
    }

    @Test
    fun `removeBrandingOrDefault rethrows a fatal Error rather than masking it as show-branding`() {
        // Best-effort swallows ordinary failures, but an OutOfMemoryError is not a billing hiccup — it must
        // propagate, not be silently downgraded to a false flag.
        every { userRepository.findById(userId) } throws OutOfMemoryError("heap")

        assertThrows<OutOfMemoryError> { service.removeBrandingOrDefault(userId) }
    }

    @Test
    fun `effectiveRemoveBranding suppresses only when the site prefers it AND the plan grants it`() {
        // The whole gate in one truth table: branding is removed iff the customer asked (hideBranding)
        // AND the plan pays for it. A paid plan the customer left on shows the credit; a free plan that
        // asked to hide it still shows the credit (the entitlement floor).
        stubUser(createdAt = now)

        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO) // entitled
        assertEquals(true, service.effectiveRemoveBranding(userId, hideBranding = true))
        assertEquals(false, service.effectiveRemoveBranding(userId, hideBranding = false))

        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.STARTER) // not entitled
        assertEquals(false, service.effectiveRemoveBranding(userId, hideBranding = true))
        assertEquals(false, service.effectiveRemoveBranding(userId, hideBranding = false))
    }

    @Test
    fun `effectiveRemoveBranding short-circuits the billing read when the site keeps the credit`() {
        // hideBranding = false can never suppress, so the plan is irrelevant — and we must not spend a
        // billing read to decide it. No user/subscription is stubbed; a lookup would throw.
        assertEquals(false, service.effectiveRemoveBranding(userId, hideBranding = false))

        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `priorityScanForSite is true only when the site owner's plan grants priority scanning`() {
        val siteId = UUID.randomUUID()
        every { siteRepository.findById(siteId) } returns Optional.of(siteOwnedBy(siteId, userId))
        stubUser(createdAt = now)

        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.BUSINESS) // priorityScan = true
        assertEquals(true, service.priorityScanForSite(siteId))

        every { subscriptionRepository.findByUserId(userId) } returns subscription(Plan.PRO) // priorityScan = false
        assertEquals(false, service.priorityScanForSite(siteId))
    }

    @Test
    fun `priorityScanForSite falls back to the normal tier when the site is gone`() {
        // A since-archived-and-purged site must still let a scan enqueue at normal priority, never drop it.
        val siteId = UUID.randomUUID()
        every { siteRepository.findById(siteId) } returns Optional.empty()

        assertEquals(false, service.priorityScanForSite(siteId))
    }

    private fun siteOwnedBy(
        siteId: UUID,
        ownerId: UUID,
    ): SiteEntity =
        SiteEntity(
            id = siteId,
            userId = ownerId,
            domain = "site-$siteId.example.com",
            siteKey = "pk_$siteId",
            status = SiteStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
}
