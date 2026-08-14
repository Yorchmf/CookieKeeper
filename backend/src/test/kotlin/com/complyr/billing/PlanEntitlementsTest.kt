package com.complyr.billing

import org.junit.jupiter.api.Test
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the per-plan entitlements to the pricing table in docs/ARCHITECTURE.md §10 so a stray edit to
 * a limit can't silently ship. If §10 changes, update both here and [Plan] together.
 */
class PlanEntitlementsTest {
    @Test
    fun `Starter is 1 site, monthly rescan, 12-month retention, branded, no extras`() {
        val e = Plan.STARTER.entitlements
        assertEquals(1, e.maxSites)
        assertEquals(RescanFrequency.MONTHLY, e.rescanFrequency)
        assertFalse(e.onDemandRescan)
        assertFalse(e.priorityScan)
        assertEquals(Period.ofMonths(12), e.consentRetention)
        assertFalse(e.removeBranding)
        assertFalse(e.csvExport)
        assertFalse(e.crossSiteAnalytics)
    }

    @Test
    fun `Pro is 3 sites, weekly plus on-demand rescan, 3-year retention, unbranded`() {
        val e = Plan.PRO.entitlements
        assertEquals(3, e.maxSites)
        assertEquals(RescanFrequency.WEEKLY, e.rescanFrequency)
        assertTrue(e.onDemandRescan)
        assertFalse(e.priorityScan)
        assertEquals(Period.ofYears(3), e.consentRetention)
        assertTrue(e.removeBranding)
        assertFalse(e.csvExport)
        assertTrue(e.crossSiteAnalytics)
    }

    @Test
    fun `Business is 10 sites, priority scan and CSV export on top of Pro`() {
        val e = Plan.BUSINESS.entitlements
        assertEquals(10, e.maxSites)
        assertEquals(RescanFrequency.WEEKLY, e.rescanFrequency)
        assertTrue(e.onDemandRescan)
        assertTrue(e.priorityScan)
        assertEquals(Period.ofYears(3), e.consentRetention)
        assertTrue(e.removeBranding)
        assertTrue(e.csvExport)
        assertTrue(e.crossSiteAnalytics)
    }

    @Test
    fun `paid plans never cap consent ingestion`() {
        Plan.entries.forEach { plan ->
            assertEquals(null, plan.entitlements.consentEventCap, "${plan.name} must not cap consent ingestion")
        }
    }

    @Test
    fun `expired accounts are frozen for new sites but never blocked from recording consent`() {
        assertEquals(0, EXPIRED_ENTITLEMENTS.maxSites)
        assertFalse(EXPIRED_ENTITLEMENTS.onDemandRescan)
        assertEquals(null, EXPIRED_ENTITLEMENTS.consentEventCap)
    }
}
