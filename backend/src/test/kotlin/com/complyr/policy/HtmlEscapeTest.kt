package com.complyr.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HtmlEscapeTest {
    @Test
    fun `escapes the five HTML-significant characters`() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlEscape.escape("&<>\"'"))
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("Acme GmbH — Straße 1", HtmlEscape.escape("Acme GmbH — Straße 1"))
    }

    @Test
    fun `neutralizes a script payload`() {
        assertEquals(
            "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;",
            HtmlEscape.escape("<script>alert('x')</script>"),
        )
    }
}
