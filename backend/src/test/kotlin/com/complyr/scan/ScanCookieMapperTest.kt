package com.complyr.scan

import com.microsoft.playwright.options.Cookie
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure mapping from Playwright cookies to persistable rows — no browser needed. */
class ScanCookieMapperTest {
    private val scanId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    // Generous caps so the shape-mapping tests aren't perturbed by the abuse guards.
    private val caps = ScanCookieMapper.Caps(maxCookies = 500, maxCookieNameLength = 256)

    @Test
    fun `maps name domain and a persistent expiry to an ISO instant`() {
        val cookie = Cookie("_ga", "GA1.2").setDomain(".example.com").setExpires(1_893_456_000.0)

        val result = ScanCookieMapper.toEntities(scanId, listOf(cookie), caps)

        assertEquals(1, result.rows.size)
        val row = result.rows.first()
        assertEquals(scanId, row.scanId)
        assertEquals("_ga", row.name)
        assertEquals(".example.com", row.domain)
        assertEquals("2030-01-01T00:00:00Z", row.expiry)
        assertEquals(false, row.isKnown, "classification is slice 3")
        assertFalse(result.wasCapped, "a single cookie is nowhere near the cap")
    }

    @Test
    fun `treats a null or non-positive expiry as a session cookie`() {
        val noExpiry = Cookie("sid", "x").setDomain("example.com") // expires defaults to null
        val sessionSentinel = Cookie("csrf", "y").setDomain("example.com").setExpires(-1.0)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(noExpiry, sessionSentinel), caps).rows

        assertEquals(ScanCookieMapper.SESSION_EXPIRY, rows[0].expiry)
        assertEquals(ScanCookieMapper.SESSION_EXPIRY, rows[1].expiry)
    }

    @Test
    fun `treats an out-of-range expiry as a session cookie instead of throwing`() {
        // Cookie.expires is attacker-influenced; a value that saturates toLong() to Long.MAX_VALUE
        // would make Instant.ofEpochSecond throw. Fail closed to a session cookie, don't crash the scan.
        val hostile = Cookie("evil", "v").setDomain("example.com").setExpires(1e300)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(hostile), caps).rows

        assertEquals(ScanCookieMapper.SESSION_EXPIRY, rows.first().expiry)
    }

    @Test
    fun `de-duplicates cookies sharing a name and domain`() {
        // Same (name, domain) on two paths collapses to one row — the schema has no path column.
        val a = Cookie("dup", "1").setDomain("example.com").setPath("/")
        val b = Cookie("dup", "2").setDomain("example.com").setPath("/app")
        val distinctDomain = Cookie("dup", "3").setDomain("other.example.com")

        val rows = ScanCookieMapper.toEntities(scanId, listOf(a, b, distinctDomain), caps).rows

        assertEquals(2, rows.size, "same name+domain collapses; different domain stays")
    }

    @Test
    fun `caps the number of rows at maxCookies and flags the overflow`() {
        // A hostile site can drop far more cookies than a legitimate one; the cap bounds how many
        // rows one scan can persist (and the downstream classify/insert work).
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 2, maxCookieNameLength = 256)
        val cookies = (1..5).map { Cookie("c$it", "v").setDomain("example.com") }

        val result = ScanCookieMapper.toEntities(scanId, cookies, tightCaps)

        assertEquals(2, result.rows.size, "excess cookies beyond the cap are dropped")
        assertTrue(result.wasCapped, "more distinct cookies than the cap means observations were dropped")
    }

    @Test
    fun `does not flag a cap hit when the distinct count exactly equals maxCookies`() {
        // Boundary: exactly maxCookies distinct cookies fit with nothing dropped, so the operator
        // WARN must not fire (it would falsely claim "excess cookies were not recorded").
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 2, maxCookieNameLength = 256)
        val cookies = (1..2).map { Cookie("c$it", "v").setDomain("example.com") }

        val result = ScanCookieMapper.toEntities(scanId, cookies, tightCaps)

        assertEquals(2, result.rows.size)
        assertFalse(result.wasCapped, "exactly-at-cap dropped nothing")
    }

    @Test
    fun `applies the count cap to distinct cookies, not raw duplicates`() {
        // De-dup runs before the cap, so duplicates don't consume cap budget.
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 2, maxCookieNameLength = 256)
        val dupA = Cookie("a", "1").setDomain("example.com").setPath("/")
        val dupAAgain = Cookie("a", "2").setDomain("example.com").setPath("/app")
        val b = Cookie("b", "3").setDomain("example.com")

        val result = ScanCookieMapper.toEntities(scanId, listOf(dupA, dupAAgain, b), tightCaps)

        assertEquals(2, result.rows.size, "two distinct names fit under the cap despite the duplicate")
        assertEquals(setOf("a", "b"), result.rows.map { it.name }.toSet())
        assertFalse(result.wasCapped, "only two distinct cookies — the duplicate isn't an overflow")
    }

    @Test
    fun `truncates a cookie name longer than maxCookieNameLength`() {
        // A single cookie name can be ~4KB in a browser; truncate attacker-controlled names so one
        // scan can't bloat scan_cookies (the column is unbounded text).
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 500, maxCookieNameLength = 8)
        val longName = "x".repeat(100)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(Cookie(longName, "v").setDomain("example.com")), tightCaps).rows

        assertEquals(8, rows.first().name.length, "over-long names are truncated to the cap")
        assertEquals("x".repeat(8), rows.first().name)
    }

    @Test
    fun `collapses over-long names that share a truncated prefix on the same domain`() {
        // Truncation runs before de-dup, so two names that differ only past maxCookieNameLength
        // persist as one row instead of slipping past a dedup keyed on the raw name.
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 500, maxCookieNameLength = 8)
        val prefix = "x".repeat(8)
        val a = Cookie(prefix + "AAAA", "1").setDomain("example.com")
        val b = Cookie(prefix + "BBBB", "2").setDomain("example.com")

        val rows = ScanCookieMapper.toEntities(scanId, listOf(a, b), tightCaps).rows

        assertEquals(1, rows.size, "same truncated name+domain collapses to one row")
        assertEquals(prefix, rows.first().name)
    }

    @Test
    fun `leaves a name within maxCookieNameLength untouched`() {
        val tightCaps = ScanCookieMapper.Caps(maxCookies = 500, maxCookieNameLength = 8)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(Cookie("short", "v").setDomain("example.com")), tightCaps).rows

        assertEquals("short", rows.first().name)
    }
}
