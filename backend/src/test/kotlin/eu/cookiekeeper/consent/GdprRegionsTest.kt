package eu.cookiekeeper.consent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The classifier decides whether a visitor may legally be shown *no* banner, so the tests are
 * written from that direction: everything ambiguous must land on [GdprRegions.IN_SCOPE] or null,
 * never on [GdprRegions.OUT_OF_SCOPE].
 */
class GdprRegionsTest {
    @Test
    fun `EU, EEA, UK and Swiss visitors are in scope`() {
        listOf("DE", "FR", "IE", "PT", "NO", "IS", "LI", "GB", "GG", "JE", "IM", "GI", "CH")
            .forEach { assertEquals(GdprRegions.IN_SCOPE, GdprRegions.classify(it), "$it must be in scope") }
    }

    @Test
    fun `EU outermost regions Cloudflare reports separately are in scope`() {
        // Cloudflare reports Réunion, Guadeloupe, Martinique, Mayotte, French Guiana, Saint-Martin
        // and Åland under their own codes, not under FR/FI — GDPR applies there all the same.
        listOf("RE", "GP", "MQ", "YT", "GF", "MF", "AX")
            .forEach { assertEquals(GdprRegions.IN_SCOPE, GdprRegions.classify(it), "$it must be in scope") }
    }

    @Test
    fun `visitors elsewhere are out of scope`() {
        listOf("US", "BR", "JP", "AU", "CA", "IN")
            .forEach { assertEquals(GdprRegions.OUT_OF_SCOPE, GdprRegions.classify(it), "$it must be out of scope") }
    }

    @Test
    fun `an absent, unknown or malformed header yields no verdict rather than a wrong one`() {
        // Null is not an error: the widget reads it as "show the banner".
        assertNull(GdprRegions.classify(null), "no header at all")
        assertNull(GdprRegions.classify("XX"), "Cloudflare could not geolocate the client")
        assertNull(GdprRegions.classify(""), "empty header")
        assertNull(GdprRegions.classify("DEU"), "three-letter code is not what Cloudflare sends")
        assertNull(GdprRegions.classify("D"), "single letter")
        assertNull(GdprRegions.classify("D3"), "digits are not a country")
        assertNull(GdprRegions.classify("D-"), "punctuation is not a country")
    }

    @Test
    fun `case and surrounding whitespace do not change the verdict`() {
        assertEquals(GdprRegions.IN_SCOPE, GdprRegions.classify("de"))
        assertEquals(GdprRegions.IN_SCOPE, GdprRegions.classify(" DE "))
        assertEquals(GdprRegions.OUT_OF_SCOPE, GdprRegions.classify("us"))
    }

    @Test
    fun `Tor exit traffic is classified, not treated as unknown`() {
        // Cloudflare sends T1 for Tor. It fails the letters-only shape check, so it lands on null —
        // the fail-open bucket — rather than being silently called out of scope.
        assertNull(GdprRegions.classify("T1"))
    }
}
