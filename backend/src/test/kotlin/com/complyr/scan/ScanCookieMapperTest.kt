package com.complyr.scan

import com.microsoft.playwright.options.Cookie
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** Pure mapping from Playwright cookies to persistable rows — no browser needed. */
class ScanCookieMapperTest {
    private val scanId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @Test
    fun `maps name domain and a persistent expiry to an ISO instant`() {
        val cookie = Cookie("_ga", "GA1.2").setDomain(".example.com").setExpires(1_893_456_000.0)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(cookie))

        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(scanId, row.scanId)
        assertEquals("_ga", row.name)
        assertEquals(".example.com", row.domain)
        assertEquals("2030-01-01T00:00:00Z", row.expiry)
        assertEquals(false, row.isKnown, "classification is slice 3")
    }

    @Test
    fun `treats a null or non-positive expiry as a session cookie`() {
        val noExpiry = Cookie("sid", "x").setDomain("example.com") // expires defaults to null
        val sessionSentinel = Cookie("csrf", "y").setDomain("example.com").setExpires(-1.0)

        val rows = ScanCookieMapper.toEntities(scanId, listOf(noExpiry, sessionSentinel))

        assertEquals(ScanCookieMapper.SESSION_EXPIRY, rows[0].expiry)
        assertEquals(ScanCookieMapper.SESSION_EXPIRY, rows[1].expiry)
    }

    @Test
    fun `de-duplicates cookies sharing a name and domain`() {
        // Same (name, domain) on two paths collapses to one row — the schema has no path column.
        val a = Cookie("dup", "1").setDomain("example.com").setPath("/")
        val b = Cookie("dup", "2").setDomain("example.com").setPath("/app")
        val distinctDomain = Cookie("dup", "3").setDomain("other.example.com")

        val rows = ScanCookieMapper.toEntities(scanId, listOf(a, b, distinctDomain))

        assertEquals(2, rows.size, "same name+domain collapses; different domain stays")
    }
}
