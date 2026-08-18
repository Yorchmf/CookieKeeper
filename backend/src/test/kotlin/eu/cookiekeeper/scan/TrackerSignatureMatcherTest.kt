package eu.cookiekeeper.scan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure host → tracker matcher. These pin the normalization and fallback rules ported from the
 * reference scanner's `matchTracker`, using a tiny in-test dataset so the assertions don't drift when
 * the bundled `trackers.json` gains rows. The observed host is attacker-influenced, so the tests cover
 * the shapes a hostile or CDN-sharded page actually produces (leading dot, `www.`, subdomains, case).
 */
class TrackerSignatureMatcherTest {
    private val signatures =
        listOf(
            TrackerSignature("doubleclick.net", "Google DoubleClick", "marketing"),
            TrackerSignature("connect.facebook.net", "Facebook Connect", "marketing"),
            TrackerSignature("google-analytics.com", "Google Analytics", "analytics"),
        )
    private val matcher = TrackerSignatureMatcher(signatures)

    @Test
    fun `an exact host matches its signature`() {
        assertEquals("Google DoubleClick", matcher.match("doubleclick.net")?.name)
    }

    @Test
    fun `a subdomain matches via the root-domain fallback`() {
        // region1.doubleclick.net -> root doubleclick.net.
        assertEquals("doubleclick.net", matcher.match("region1.doubleclick.net")?.domain)
    }

    @Test
    fun `a deeper subdomain of a multi-label key matches via the suffix scan`() {
        // The root of a.b.connect.facebook.net is facebook.net (not a key); the suffix rule catches it.
        assertEquals("connect.facebook.net", matcher.match("a.b.connect.facebook.net")?.domain)
    }

    @Test
    fun `a leading dot and www prefix are stripped before matching`() {
        assertEquals("doubleclick.net", matcher.match(".www.doubleclick.net")?.domain)
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals("doubleclick.net", matcher.match("AD.DoubleClick.NET")?.domain)
    }

    @Test
    fun `an unrelated host does not match`() {
        assertNull(matcher.match("example.com"))
    }

    @Test
    fun `an empty host does not match`() {
        assertNull(matcher.match(""))
    }
}
