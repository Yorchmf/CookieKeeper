package com.complyr.site

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetMatcherTest {
    private val siteKey = "pk_AbC123"
    private val cdnHost = "cdn.complyr.eu"

    private fun matches(html: String): Boolean = SnippetMatcher.matches(html, siteKey, cdnHost)

    @Test
    fun `matches the snippet exactly as the dashboard hands it out`() {
        val html = """<html><head><script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script></head></html>"""

        assertTrue(matches(html))
    }

    @Test
    fun `is tolerant of quote style, attribute order, casing and extra attributes`() {
        assertTrue(matches("""<SCRIPT DATA-COMPLYR='pk_AbC123' defer SRC='//cdn.complyr.eu/v1.js'></SCRIPT>"""))
        assertTrue(matches("""<script data-complyr=pk_AbC123 src=https://CDN.Complyr.EU/v1.js></script>"""))
        assertTrue(matches("""<script crossorigin="anonymous"  src="https://cdn.complyr.eu/v1.js"  data-complyr="pk_AbC123" ></script>"""))
    }

    @Test
    fun `matches inside minified markup with no whitespace between tags`() {
        val html =
            """<!doctype html><html><head><meta charset="utf-8"><title>x</title>""" +
                """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123" async></script>""" +
                """<script src="https://example.com/app.js"></script></head><body>hi</body></html>"""

        assertTrue(matches(html))
    }

    @Test
    fun `rejects a bare site key in page text (the user-generated-content forgery bypass)`() {
        // An attacker who registers victim.com is issued pk_AbC123, then posts a comment containing that
        // literal string. If verification were a substring search, the victim's own homepage would prove
        // the attacker's ownership. Only a real script tag counts.
        assertFalse(matches("""<html><body><p>My verification code is pk_AbC123, please help!</p></body></html>"""))
        assertFalse(matches("""<html><body><!-- data-complyr="pk_AbC123" --></body></html>"""))
        assertFalse(matches("""<html><body><div data-complyr="pk_AbC123"></div></body></html>"""))
    }

    @Test
    fun `rejects a script tag that is not loaded from our CDN host`() {
        assertFalse(matches("""<script src="https://evil.example.com/v1.js" data-complyr="pk_AbC123"></script>"""))
        // The CDN host appearing in the path, not the authority, must not count.
        assertFalse(matches("""<script src="https://evil.example.com/cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""))
        // …nor as a userinfo prefix.
        assertFalse(matches("""<script src="https://cdn.complyr.eu@evil.example.com/v1.js" data-complyr="pk_AbC123"></script>"""))
        // …nor as a sub-domain-looking suffix.
        assertFalse(matches("""<script src="https://cdn.complyr.eu.evil.example.com/v1.js" data-complyr="pk_AbC123"></script>"""))
    }

    @Test
    fun `rejects our CDN script carrying a different site key`() {
        assertFalse(matches("""<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_someoneelse"></script>"""))
        // A key that merely starts with ours is not ours.
        assertFalse(matches("""<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123456"></script>"""))
    }

    @Test
    fun `rejects an attribute whose name only contains data-complyr`() {
        assertFalse(matches("""<script src="https://cdn.complyr.eu/v1.js" x-data-complyr="pk_AbC123"></script>"""))
    }

    @Test
    fun `rejects a tag truncated mid-attribute by the fetcher's byte cap`() {
        // A false negative is the safe direction: never match on a half-read attribute list.
        assertFalse(matches("""<html><head><script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC12"""))
    }

    @Test
    fun `keeps scanning past a truncated-looking quote and a tag that only resembles script`() {
        // `<scripting` must not be consumed as a script tag and swallow the real one that follows.
        val html =
            """<scripting-note data-complyr="pk_AbC123"></scripting-note>""" +
                """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""

        assertTrue(matches(html))
    }

    @Test
    fun `is not confused by a greater-than character inside an attribute value`() {
        val html =
            """<script data-x="a>b" src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""

        assertTrue(matches(html))
    }

    @Test
    fun `rejects empty inputs rather than matching everything`() {
        val html = """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""

        assertFalse(SnippetMatcher.matches(html, siteKey = "", cdnHost = cdnHost))
        assertFalse(SnippetMatcher.matches(html, siteKey = siteKey, cdnHost = ""))
        assertFalse(matches(""))
    }

    @Test
    fun `matches a snippet carrying a cache-busting query string`() {
        // `java.net.URI` follows RFC 2396, which is stricter than what browsers accept in a query — `|`,
        // `^`, `{}` and spaces are all legal to a browser and illegal to URI. Since the authority always
        // ends before the query, the query is dropped before parsing; letting it throw would present to
        // the customer as an unexplained "we can't find your snippet".
        assertTrue(matches("""<script src="https://cdn.complyr.eu/v1.js?v={build}|1" data-complyr="pk_AbC123"></script>"""))
        assertTrue(matches("""<script src="https://cdn.complyr.eu/v1.js#frag" data-complyr="pk_AbC123"></script>"""))
    }

    @Test
    fun `matches on a page whose content carries the characters the tokenizer alignment rewrites`() {
        // Alignment substitutes U+0130/U+0131 (Turkish) and stray C0 controls document-wide. Real pages
        // contain both — in prose, in JSON islands, in minified JS — and none of it may cost a customer
        // their verification, because the snippet itself is pure ASCII.
        val html =
            "<html><head><title>İstanbul Bilişim</title>" +
                "<script>var t=\"kalabalık\";</script>" +
                """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>""" +
                "</head><body>İı </body></html>"

        assertTrue(matches(html))
    }

    @Test
    fun `matches a script in an svg HTML integration point, where browsers do execute`() {
        // `<svg><title>` and `<foreignObject>` switch the parser back to the HTML namespace, so a script
        // there is a real HTML script element. The foreign-content rejection must not overreach into these.
        val html =
            """<svg><foreignObject>""" +
                """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>""" +
                """</foreignObject></svg>"""

        assertTrue(matches(html))
    }

    @Test
    fun `finds the snippet however many script tags precede it`() {
        // The previous byte-scan capped itself at 2000 inspected tags, which made a page with many script
        // tags un-verifiable — a denial-of-verification a hostile ad or CMS could trigger by accident.
        // A single linear parse has no such cliff, and the fetcher's 512KB body cap is the real bound.
        val real = """<script src="https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""

        assertTrue(matches("<script></script>".repeat(3000) + real))
    }
}
