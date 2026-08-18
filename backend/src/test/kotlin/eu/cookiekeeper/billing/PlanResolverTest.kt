package eu.cookiekeeper.billing

import eu.cookiekeeper.common.CookieKeeperProperties
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlanResolverTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val trialPeriod = Duration.ofDays(14)
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
            billing =
                CookieKeeperProperties.Billing(
                    trialPeriod = trialPeriod,
                    trialConsentEventCap = trialCap,
                ),
            appBaseUrl = "http://localhost:3000",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val resolver = PlanResolver(properties, Clock.fixed(now, ZoneOffset.UTC))

    private fun subscription(
        status: String,
        plan: Plan = Plan.PRO,
    ): SubscriptionEntity =
        SubscriptionEntity(
            userId = UUID.randomUUID(),
            stripeCustomerId = "cus_123",
            stripeSubId = "sub_123",
            plan = plan,
            status = status,
            periodEnd = now.plus(Duration.ofDays(30)),
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `an active subscription resolves to Subscribed on its plan`() {
        val result =
            resolver.resolve(
                accountCreatedAt = now.minus(Duration.ofDays(90)),
                subscription(status = "active", plan = Plan.PRO),
            )

        val subscribed = assertIs<AccountEntitlement.Subscribed>(result)
        assertEquals(Plan.PRO, subscribed.plan)
        assertEquals(Plan.PRO.entitlements, subscribed.entitlements)
    }

    @Test
    fun `a Stripe trialing subscription counts as active`() {
        val result =
            resolver.resolve(
                accountCreatedAt = now.minus(Duration.ofDays(90)),
                subscription(status = "trialing", plan = Plan.BUSINESS),
            )

        assertIs<AccountEntitlement.Subscribed>(result)
    }

    @Test
    fun `a paid subscription wins even inside the original trial window`() {
        // Account created 1 day ago (well within the 14-day trial) but already subscribed.
        val result =
            resolver.resolve(
                accountCreatedAt = now.minus(Duration.ofDays(1)),
                subscription(status = "active", plan = Plan.STARTER),
            )

        assertIs<AccountEntitlement.Subscribed>(result)
    }

    @Test
    fun `no subscription within the trial window resolves to Trial with the consent cap`() {
        val createdAt = now.minus(Duration.ofDays(3))
        val result = resolver.resolve(accountCreatedAt = createdAt, subscription = null)

        val trial = assertIs<AccountEntitlement.Trial>(result)
        assertEquals(createdAt.plus(trialPeriod), trial.endsAt)
        assertEquals(trialCap, trial.entitlements.consentEventCap)
        // Trial is Starter-shaped apart from the cap.
        assertEquals(Plan.STARTER.entitlements.maxSites, trial.entitlements.maxSites)
        assertEquals(Period.ofMonths(12), trial.entitlements.consentRetention)
    }

    @Test
    fun `an inactive subscription within the trial window still resolves to Trial`() {
        // e.g. a past_due / canceled sub that never became active; the trial window still governs.
        val result = resolver.resolve(accountCreatedAt = now.minus(Duration.ofDays(2)), subscription(status = "past_due"))

        assertIs<AccountEntitlement.Trial>(result)
    }

    @Test
    fun `no subscription after the trial window resolves to Expired`() {
        val result = resolver.resolve(accountCreatedAt = now.minus(Duration.ofDays(20)), subscription = null)

        assertEquals(AccountEntitlement.Expired, result)
        assertEquals(0, result.entitlements.maxSites)
        // Expired never blocks recording consent — the cap stays unbounded.
        assertEquals(null, result.entitlements.consentEventCap)
    }

    @Test
    fun `an inactive subscription after the trial window resolves to Expired`() {
        val result = resolver.resolve(accountCreatedAt = now.minus(Duration.ofDays(30)), subscription(status = "canceled"))

        assertEquals(AccountEntitlement.Expired, result)
    }

    @Test
    fun `the exact trial boundary is treated as expired`() {
        // endsAt == now → not before now → Expired (the trial is a half-open window).
        val result = resolver.resolve(accountCreatedAt = now.minus(trialPeriod), subscription = null)

        assertEquals(AccountEntitlement.Expired, result)
    }
}
