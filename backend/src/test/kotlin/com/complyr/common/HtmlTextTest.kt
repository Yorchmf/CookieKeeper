package com.complyr.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HtmlTextTest {
    @Test
    fun `leaves plain text untouched`() {
        assertEquals("example.com", HtmlText.escape("example.com"))
    }

    @Test
    fun `escapes the five HTML-significant characters`() {
        assertEquals("&amp;", HtmlText.escape("&"))
        assertEquals("&lt;", HtmlText.escape("<"))
        assertEquals("&gt;", HtmlText.escape(">"))
        assertEquals("&quot;", HtmlText.escape("\""))
        assertEquals("&#39;", HtmlText.escape("'"))
    }

    @Test
    fun `neutralizes a script-tag injection so it cannot break out of text content`() {
        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            HtmlText.escape("<script>alert(1)</script>"),
        )
    }

    @Test
    fun `neutralizes an attribute breakout with quotes and event handler`() {
        assertEquals(
            "&quot; onmouseover=&quot;x&quot;",
            HtmlText.escape("\" onmouseover=\"x\""),
        )
    }

    @Test
    fun `escapes ampersand first so emitted entities are not double-escaped`() {
        // "&<" must become "&amp;&lt;", never "&amp;lt;" (which would happen if < were escaped before &).
        assertEquals("&amp;&lt;", HtmlText.escape("&<"))
    }

    @Test
    fun `preserves non-ASCII IDN and localized text instead of entity-encoding it`() {
        // Unlike HtmlUtils.htmlEscape, accented and IDN characters pass through verbatim.
        assertEquals("münchen-café.eu", HtmlText.escape("münchen-café.eu"))
    }
}
