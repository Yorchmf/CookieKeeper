package com.complyr.scan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure matching precedence — no database. Exact beats wildcard; among wildcards the longest matching
 * prefix wins; a name that matches nothing returns null (caller flags it "needs review").
 */
class CookieSignatureMatcherTest {
    private val signatures =
        listOf(
            CookieSignature("_ga", isWildcard = false, provider = "Google Analytics", category = "statistics"),
            CookieSignature("_ga_", isWildcard = true, provider = "Google Analytics", category = "statistics"),
            CookieSignature("_gat_", isWildcard = true, provider = "Google Analytics", category = "statistics"),
            CookieSignature("_gcl_au", isWildcard = false, provider = "Google Ads", category = "marketing"),
            CookieSignature("_gcl_", isWildcard = true, provider = "Google Ads", category = "marketing"),
            CookieSignature("PHPSESSID", isWildcard = false, provider = "PHP", category = "necessary"),
        )
    private val matcher = CookieSignatureMatcher(signatures)

    @Test
    fun `exact name matches its signature`() {
        val hit = matcher.match("PHPSESSID")

        assertEquals(CookieClassification("necessary", "PHP"), hit)
    }

    @Test
    fun `wildcard prefix matches a family instance`() {
        // GA4 emits _ga_<container-id>; only the _ga_ wildcard should catch it.
        val hit = matcher.match("_ga_ABC123DEF")

        assertEquals(CookieClassification("statistics", "Google Analytics"), hit)
    }

    @Test
    fun `exact match wins over an overlapping wildcard`() {
        // "_gcl_au" is both an exact signature and a prefix of the "_gcl_" wildcard; exact must win.
        val hit = matcher.match("_gcl_au")

        assertEquals(CookieClassification("marketing", "Google Ads"), hit)
    }

    @Test
    fun `longest matching wildcard prefix wins`() {
        // "_gat_gtag_UA_1" starts with the _gat_ wildcard but not _ga_ (char 4 differs) — most specific.
        val hit = matcher.match("_gat_gtag_UA_1")

        assertEquals(CookieClassification("statistics", "Google Analytics"), hit)
    }

    @Test
    fun `among two overlapping wildcards the longer prefix wins`() {
        // Both wildcards match "_ab_c_123"; the more specific (longer) prefix must decide the category,
        // which is exactly what the length sort guarantees — a plain unsorted scan could pick either.
        val overlapping =
            listOf(
                CookieSignature("_ab_", isWildcard = true, provider = "Broad", category = "statistics"),
                CookieSignature("_ab_c_", isWildcard = true, provider = "Specific", category = "marketing"),
            )

        val hit = CookieSignatureMatcher(overlapping).match("_ab_c_123")

        assertEquals(CookieClassification("marketing", "Specific"), hit)
    }

    @Test
    fun `an empty wildcard prefix is ignored rather than matching everything`() {
        // A bad seed row with an empty pattern must NOT classify every cookie (startsWith("") is always true).
        val withEmpty = listOf(CookieSignature("", isWildcard = true, provider = "Bad", category = "marketing"))

        assertNull(CookieSignatureMatcher(withEmpty).match("anything"))
    }

    @Test
    fun `exact signature name does not match a longer cookie name`() {
        // "_ga" is exact-only; "_gaXYZ" is not "_ga" and does not start with any wildcard here.
        assertNull(matcher.match("_gaXYZ"))
    }

    @Test
    fun `unknown cookie name returns null`() {
        assertNull(matcher.match("myapp_custom_flag"))
    }

    @Test
    fun `an empty signature set matches nothing`() {
        assertNull(CookieSignatureMatcher(emptyList()).match("_ga"))
    }
}
