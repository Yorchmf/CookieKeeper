package eu.cookiekeeper.billing.dto

import eu.cookiekeeper.billing.AccountEntitlement
import eu.cookiekeeper.billing.EntitlementSummary
import eu.cookiekeeper.billing.Plan
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [EntitlementResponse.from] — the sealed [AccountEntitlement] → wire-DTO mapping the
 * dashboard billing page consumes. Asserts the stable `state` strings, that `plan`/`trialEndsAt` are
 * populated only for the states that own them, and that the [Entitlements] limits (retention as whole
 * months) and usage count pass through.
 */
class EntitlementResponseTest {
    private val trialEndsAt = Instant.parse("2026-08-15T00:00:00Z")

    @Test
    fun `Trial maps to the trial state with an end date and no purchased plan`() {
        val summary =
            EntitlementSummary(
                entitlement = AccountEntitlement.Trial(endsAt = trialEndsAt, entitlements = Plan.STARTER.entitlements),
                activeSites = 0,
                consentEventsUsed = 250,
            )

        val response = EntitlementResponse.from(summary)

        assertEquals("trial", response.state)
        assertNull(response.plan, "the trial is Starter-shaped, not a bought plan")
        assertEquals(trialEndsAt, response.trialEndsAt)
        assertEquals(1, response.limits.maxSites)
        assertEquals(0, response.activeSites)
        // The trial usage count feeds the dashboard meter against limits.consentEventCap.
        assertEquals(250, response.consentEventsUsed)
    }

    @Test
    fun `Subscribed maps to the subscribed state carrying the plan name`() {
        val summary = EntitlementSummary(AccountEntitlement.Subscribed(Plan.BUSINESS), activeSites = 3)

        val response = EntitlementResponse.from(summary)

        assertEquals("subscribed", response.state)
        assertEquals("BUSINESS", response.plan)
        assertNull(response.trialEndsAt)
        assertNull(response.consentEventsUsed, "the consent cap and its meter are trial-only")
        assertEquals(10, response.limits.maxSites)
        assertEquals(true, response.limits.csvExport, "Business unlocks CSV export")
        assertEquals(true, response.limits.crossSiteAnalytics, "Business unlocks cross-site analytics")
        assertEquals(3, response.activeSites)
        // Paid plans keep 3 years of consent evidence — exposed as whole months.
        assertEquals(36, response.limits.consentRetentionMonths)
    }

    @Test
    fun `Expired maps to the expired state with a zero site cap`() {
        val response = EntitlementResponse.from(EntitlementSummary(AccountEntitlement.Expired, activeSites = 1))

        assertEquals("expired", response.state)
        assertNull(response.plan)
        assertNull(response.trialEndsAt)
        assertEquals(0, response.limits.maxSites, "expired accounts cannot add sites")
        // Usage can exceed the (zero) cap — existing sites are never torn down on expiry.
        assertEquals(1, response.activeSites)
    }
}
