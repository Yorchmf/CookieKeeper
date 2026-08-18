package eu.cookiekeeper.scan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure crawl-scope decisions — no browser, no DNS. */
class ScanCrawlPolicyTest {
    @Test
    fun `quick mode caps at a single page regardless of the configured budget`() {
        assertEquals(1, ScanCrawlPolicy.effectiveMaxPages(CrawlMode.QUICK, configuredMaxPages = 10))
        assertEquals(1, ScanCrawlPolicy.effectiveMaxPages(CrawlMode.QUICK, configuredMaxPages = 1))
    }

    @Test
    fun `full mode uses the configured page budget`() {
        assertEquals(10, ScanCrawlPolicy.effectiveMaxPages(CrawlMode.FULL, configuredMaxPages = 10))
    }

    @Test
    fun `same host family covers apex, www, and sub-domains but not lookalikes`() {
        assertTrue(ScanCrawlPolicy.sameHostFamily("example.com", "example.com"), "apex matches itself")
        assertTrue(ScanCrawlPolicy.sameHostFamily("www.example.com", "example.com"), "www is the same site")
        assertTrue(ScanCrawlPolicy.sameHostFamily("example.com", "www.example.com"), "apex matches a www domain")
        assertTrue(ScanCrawlPolicy.sameHostFamily("shop.example.com", "example.com"), "sub-domain is the same site")

        assertFalse(ScanCrawlPolicy.sameHostFamily("evil.com", "example.com"), "unrelated host is foreign")
        assertFalse(
            ScanCrawlPolicy.sameHostFamily("notexample.com", "example.com"),
            "suffix without a dot boundary must not match",
        )
        assertFalse(
            ScanCrawlPolicy.sameHostFamily("example.com.evil.com", "example.com"),
            "a domain embedded as a left label of another host is foreign",
        )
    }

    @Test
    fun `a www-configured domain does not treat sibling sub-domains as same-host`() {
        // Documents the preserved behavior: with a www. crawl domain, only the apex and the www host
        // match — a sibling like shop.example.com is NOT folded in (it's not a sub-domain of www).
        assertTrue(ScanCrawlPolicy.sameHostFamily("www.example.com", "www.example.com"), "www matches itself")
        assertTrue(ScanCrawlPolicy.sameHostFamily("example.com", "www.example.com"), "apex matches a www domain")
        assertFalse(
            ScanCrawlPolicy.sameHostFamily("shop.example.com", "www.example.com"),
            "a sibling sub-domain of a www-configured domain is not same-host",
        )
    }

    @Test
    fun `same host family is case-insensitive`() {
        assertTrue(ScanCrawlPolicy.sameHostFamily("WWW.Example.COM", "example.com"))
    }

    @Test
    fun `normalize keeps same-host http links and strips the fragment`() {
        assertEquals(
            "https://example.com/pricing?ref=1",
            ScanCrawlPolicy.normalizeSameHost("https://example.com/pricing?ref=1#section", "example.com"),
        )
        assertEquals(
            "https://www.example.com/about",
            ScanCrawlPolicy.normalizeSameHost("https://www.example.com/about", "example.com"),
        )
    }

    @Test
    fun `normalize rejects off-host, non-http, and unparseable links`() {
        assertNull(ScanCrawlPolicy.normalizeSameHost("https://evil.com/x", "example.com"), "off-host link")
        assertNull(
            ScanCrawlPolicy.normalizeSameHost("https://example.com@evil.com/", "example.com"),
            "userinfo confusion: the real host is evil.com, not example.com",
        )
        assertNull(ScanCrawlPolicy.normalizeSameHost("mailto:hi@example.com", "example.com"), "non-http scheme")
        assertNull(ScanCrawlPolicy.normalizeSameHost("javascript:alert(1)", "example.com"), "javascript scheme")
        assertNull(ScanCrawlPolicy.normalizeSameHost("/relative/path", "example.com"), "scheme-less relative link")
    }

    @Test
    fun `hostless safe schemes are recognized, network schemes are not`() {
        assertTrue(ScanCrawlPolicy.isHostlessSafeScheme("data:text/html,hi"))
        assertTrue(ScanCrawlPolicy.isHostlessSafeScheme("blob:https://example.com/uuid"))
        assertTrue(ScanCrawlPolicy.isHostlessSafeScheme("about:blank"))

        assertFalse(ScanCrawlPolicy.isHostlessSafeScheme("https://example.com/"), "network scheme is not hostless-safe")
        assertFalse(ScanCrawlPolicy.isHostlessSafeScheme("ws://example.com/"), "websocket is not hostless-safe")
    }

    @Test
    fun `hostOf lower-cases the host and is null for hostless urls`() {
        assertEquals("example.com", ScanCrawlPolicy.hostOf("https://Example.COM/path"))
        assertNull(ScanCrawlPolicy.hostOf("about:blank"), "no host on a hostless scheme")
    }
}
