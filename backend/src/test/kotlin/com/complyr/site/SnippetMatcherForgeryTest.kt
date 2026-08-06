package com.complyr.site

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

/**
 * The forgery suite. Every case here is a page an attacker can produce on a victim domain **without**
 * being able to place a live `<script>` element — i.e. without the total compromise ADR-17 accepts as
 * unsavable. A browser executes nothing from any of these; verification must therefore reject them all.
 *
 * The payloads deliberately use unquoted attribute syntax: no `"`, `'`, `\` or newline, so they survive
 * JSON encoding, JS string literals and quote-only escapers byte-identical.
 */
class SnippetMatcherForgeryTest {
    private val siteKey = "pk_AbC123"
    private val cdnHost = "cdn.complyr.eu"

    private fun matches(html: String): Boolean = SnippetMatcher.matches(html, siteKey, cdnHost)

    /** The snippet as raw text, carrying no quotes — what an attacker submits as user content. */
    private val payload = """<script src=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123>"""

    @Test
    fun `rejects the snippet inside an HTML comment`() {
        assertFalse(matches("""<html><body><!-- user said: $payload --></body></html>"""))
    }

    @Test
    fun `rejects the snippet inside a JSON island in a script body`() {
        // Jackson and JSON.stringify do not escape `<` by default, so any reflected user string
        // serialised into a data island reproduces the payload verbatim.
        assertFalse(matches("""<html><head><script>window.__D={"q":"$payload"}</script></head></html>"""))
    }

    @Test
    fun `rejects the snippet inside JSON-LD structured data`() {
        assertFalse(
            matches("""<script type="application/ld+json">{"name":"$payload"}</script>"""),
        )
    }

    @Test
    fun `rejects the snippet inside RCDATA and inert text elements`() {
        assertFalse(matches("""<title>Search: $payload</title>"""))
        assertFalse(matches("""<textarea>$payload</textarea>"""))
        assertFalse(matches("""<xmp>$payload</xmp>"""))
        assertFalse(matches("""<svg><![CDATA[$payload]]></svg>"""))
    }

    @Test
    fun `rejects the snippet inside noscript wherever the noscript sits`() {
        // jsoup implements the scripting-DISABLED tree-construction path, in which a body-position
        // `<noscript>`'s contents are parsed as markup. A browser runs with scripting enabled and treats
        // them as raw text. The head-position case rejects for the wrong reason — `InHeadNoscript`
        // inserts a character node there — so any leading body content flips it. Both must reject.
        assertFalse(matches("""<noscript>$payload</noscript>"""))
        assertFalse(matches("""<html><body><p>hi</p><noscript>$payload</noscript></body></html>"""))
    }

    @Test
    fun `rejects the snippet inside a template, which a browser parks in an inert fragment`() {
        assertFalse(matches("""<template>$payload</template>"""))
        assertFalse(matches("""<template><div><template>$payload</template></div></template>"""))
    }

    @Test
    fun `rejects a foreign-namespace script, which is not an HTML script element`() {
        // jsoup's normalName() is `script` in every namespace, but SVGScriptElement loads from
        // `href`/`xlink:href` — never `src` — and MathML has no script element at all.
        assertFalse(matches("""<svg>$payload</svg>"""))
        assertFalse(matches("""<math>$payload</math>"""))
    }

    @Test
    fun `rejects a script closed only by a case-folding lookalike end tag`() {
        // U+0130 and U+0131 fold to ASCII i/I under Java's equalsIgnoreCase, which is what jsoup uses to
        // decide whether an end tag closes a raw-text element. HTML's script-data-end-tag-name state
        // appends ASCII alpha only, so a browser never closes the outer script and the whole payload
        // stays inert JS string data. Reachable through a plain JSON island — no markup injection needed,
        // and the payload carries no `"`, `'`, `\` or newline, so JSON encoding preserves it byte for byte.
        listOf('\u0130', '\u0131').forEach { fold ->
            assertFalse(
                matches("""<script>window.__D={"c":"</scr${fold}pt>$payload"};</script>"""),
                "an end tag spelled with U+%04X must not close a script".format(fold.code),
            )
        }
    }

    @Test
    fun `rejects a tag or attribute name padded with a C0 control`() {
        // Java's String.trim() strips every character <= U+0020, which is wider than HTML's five
        // whitespace characters — so jsoup normalizes `script\u000B` to `script` and `src\u000B` to `src`.
        // A browser sees an unknown element, or an attribute whose name simply is not `src`.
        listOf('\u0001', '\u000B', '\u001F').forEach { pad ->
            val why = "must not be trimmed away (U+%04X)".format(pad.code)
            assertFalse(matches("<script$pad src=https://cdn.complyr.eu/v1.js data-complyr=$siteKey>"), why)
            assertFalse(matches("<script$pad/src=https://cdn.complyr.eu/v1.js data-complyr=$siteKey>"), why)
            assertFalse(matches("<script src$pad=https://cdn.complyr.eu/v1.js data-complyr=$siteKey>"), why)
            assertFalse(matches("<script ${pad}src=https://cdn.complyr.eu/v1.js ${pad}data-complyr=$siteKey>"), why)
        }
    }

    @Test
    fun `rejects a padded duplicate that shadows the site's own src`() {
        // jsoup de-duplicates AFTER trimming and keeps the first, so a padded `src=<our cdn>` written
        // ahead of the site's real `src=/app.js` wins the dedupe. The browser sees an unknown attribute
        // and loads /app.js — nothing of ours.
        assertFalse(
            matches("<script src\u000B=https://cdn.complyr.eu/v1.js src=/app.js data-complyr=$siteKey>"),
        )
    }

    @Test
    fun `rejects the snippet inside another element's attribute value`() {
        assertFalse(matches("""<div title="user text $payload end"></div>"""))
    }

    @Test
    fun `rejects attributes smuggled inside a sibling attribute's quoted value`() {
        // Browser: one script with data-user and src=/app.js. No data-complyr, nothing from our CDN.
        assertFalse(
            matches(
                """<script data-user="hi src=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123 " src="/app.js"></script>""",
            ),
        )
    }

    @Test
    fun `rejects another customer's key smuggled ahead of the real one`() {
        // Browser duplicate-attribute rule keeps the FIRST data-complyr — the victim's real key.
        // A first-textual-occurrence scan must not report the attacker's injected one.
        assertFalse(
            matches(
                """<script src="https://cdn.complyr.eu/v1.js" data-bio="hello data-complyr=pk_AbC123 " """ +
                    """data-complyr="pk_VICTIMKEY"></script>""",
            ),
        )
    }

    @Test
    fun `rejects a src that only mentions the CDN host in its query or fragment`() {
        // The browser loads the victim's own /track.js; nothing comes from our CDN.
        assertFalse(matches("""<script src="/track.js?u=https://cdn.complyr.eu/v1.js" data-complyr="pk_AbC123"></script>"""))
        assertFalse(matches("""<script src="/app.js#https://cdn.complyr.eu/" data-complyr="pk_AbC123"></script>"""))
    }

    @Test
    fun `rejects a non-http scheme that happens to contain the CDN authority`() {
        assertFalse(matches("""<script src="javascript://cdn.complyr.eu/" data-complyr="pk_AbC123"></script>"""))
        assertFalse(matches("""<script src="data:text/javascript,//x://cdn.complyr.eu/" data-complyr="pk_AbC123"></script>"""))
    }

    @Test
    fun `rejects a tag name separated by non-HTML whitespace`() {
        // Java's isWhitespace is wider than HTML's (TAB LF FF CR SPACE). A browser sees an unknown
        // element named `script\u000Bsrc=...`, not a script.
        assertFalse(matches("<script\u000Bsrc=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123>"))
        assertFalse(matches("<script\u2028src=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123>"))
    }

    @Test
    fun `rejects unicode case-folding lookalikes of the tag and attribute names`() {
        // Kotlin's ignoreCase folds U+017F to s and U+212A to k; HTML does not.
        assertFalse(matches("<\u017Fcript src=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123>"))
        assertFalse(matches("<script \u017Frc=https://cdn.complyr.eu/v1.js data-complyr=pk_AbC123>"))
    }

    @Test
    fun `rejects the two required attributes split across two separate script tags`() {
        assertFalse(
            matches(
                """<script src="https://cdn.complyr.eu/v1.js"></script><script data-complyr="pk_AbC123"></script>""",
            ),
        )
    }
}
