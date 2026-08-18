package eu.cookiekeeper.common

import eu.cookiekeeper.billing.Plan
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The consent retention window is tenant-blind and dropped irreversibly a whole partition at a time
 * (ADR-16), so the startup floor MUST be the LONGEST plan retention — anything lower lets an operator
 * fat-finger `CONSENT_RETENTION_MONTHS` into silently deleting a paying customer's still-entitled
 * evidence on the next reaper run. These lock that floor and, crucially, guard it against drifting below
 * the billing `Plan` retentions (the config layer mirrors the value rather than importing `Plan`).
 */
class ConsentRetentionPropertiesTest {
    @Test
    fun `defaults to 36 months (the longest plan retention)`() {
        assertEquals(36, CookieKeeperProperties.Consent().retentionMonths)
    }

    @Test
    fun `accepts the floor and larger windows`() {
        assertEquals(
            CookieKeeperProperties.Consent.MIN_RETENTION_MONTHS,
            CookieKeeperProperties.Consent(retentionMonths = CookieKeeperProperties.Consent.MIN_RETENTION_MONTHS).retentionMonths,
        )
        // A longer window (e.g. toward the 5-yr CLAUDE.md ceiling) is always allowed.
        assertEquals(60, CookieKeeperProperties.Consent(retentionMonths = 60).retentionMonths)
    }

    @Test
    fun `rejects a window below the longest plan retention`() {
        // 35 is one month short of the 36-mo floor; 12 is the old (unsafe) shortest-plan value that used
        // to pass and would drop Pro/Business evidence still inside its 3-yr entitlement.
        assertThrows<IllegalArgumentException> { CookieKeeperProperties.Consent(retentionMonths = 35) }
        assertThrows<IllegalArgumentException> { CookieKeeperProperties.Consent(retentionMonths = 12) }
        assertThrows<IllegalArgumentException> { CookieKeeperProperties.Consent(retentionMonths = 0) }
    }

    @Test
    fun `floor never drifts below any plan's entitled retention`() {
        // The config layer deliberately does not depend on billing.Plan, so MIN_RETENTION_MONTHS is a
        // mirrored constant. This guard fails the build if a plan's retention is ever raised above it,
        // forcing the floor (and default) to be bumped in lockstep instead of silently under-retaining.
        val longestPlanRetentionMonths = Plan.entries.maxOf { it.entitlements.consentRetention.toTotalMonths() }
        assertTrue(
            CookieKeeperProperties.Consent.MIN_RETENTION_MONTHS >= longestPlanRetentionMonths,
            "MIN_RETENTION_MONTHS (${CookieKeeperProperties.Consent.MIN_RETENTION_MONTHS}) must be >= the " +
                "longest plan retention ($longestPlanRetentionMonths mo) or the reaper under-retains paying customers",
        )
    }
}
