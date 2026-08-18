package eu.cookiekeeper.site

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URISyntaxException

/**
 * Decides whether a page actually carries *our* embed snippet (ADR-17). The shape it looks for is the
 * one [SiteService] hands the customer:
 *
 * ```html
 * <script async src="https://cdn.cookiekeeper.eu/v1.js" data-cookiekeeper="pk_…"></script>
 * ```
 *
 * **Why this is not a substring search for the site key.** A bare `html.contains(siteKey)` is forgeable
 * on any site that renders user-generated content: an attacker registers `victim.com`, is issued
 * `pk_ABC`, posts a comment containing the literal text `pk_ABC` that renders on the victim's homepage,
 * and verifies a domain they do not control.
 *
 * **Why this is not a hand-rolled tag scan either.** That was the first implementation, and it was
 * broken in the same way for a subtler reason: a scan that looks for the *bytes* `<script …>` cannot
 * tell markup from text that merely looks like markup. It accepted the snippet from inside HTML
 * comments, JSON islands (`JSON.stringify` and Jackson do not escape `<`), `application/ld+json`
 * blocks, `<title>`, `<textarea>`, `<noscript>`, `<xmp>`, CDATA sections, and other elements' quoted
 * attribute values — every one of which a victim's site may render from untrusted user input while a
 * browser executes precisely nothing. Reproductions live in [SnippetMatcherForgeryTest].
 *
 * The security property we need is literally "a browser loading this page would run our script", so we
 * ask a parser browsers agree with instead of re-implementing the HTML5 tokenizer. jsoup models
 * comments, RCDATA, raw-text and attribute contexts and duplicate-attribute precedence (first wins, as
 * in browsers).
 *
 * **Where jsoup is not a browser, and what we do about it.** "Parse with a real parser" is necessary but
 * not sufficient: every place jsoup's tree differs from the browser's is a forgery, and an adversarial
 * pass found five. They fall into two families, each closed by one measure:
 *
 *  - *Tokenizer divergence.* jsoup compares tag and attribute names with Java's case and whitespace
 *    rules, which are wider than HTML's ASCII-only ones. `String.trim()` strips every character
 *    `<= U+0020`, so `<script src=…>` normalizes to a `script` element (a browser sees an unknown
 *    element), and `src=…` normalizes to `src` — which then wins the duplicate-attribute dedupe
 *    against the site's own `src`. `String.equalsIgnoreCase` folds U+0130/U+0131 onto ASCII `i`, so
 *    `</scrİpt>` closes a raw-text `<script>` in jsoup but not in a browser, promoting a payload sitting
 *    inertly inside a JSON island into a live element — **with no markup injection at all**.
 *    [alignTokenizerWithBrowsers] replaces exactly the characters that cause these divergences before
 *    parsing, which is strictly conservative: a sentinel can only lengthen a name, never split a token.
 *  - *Tree-position divergence.* jsoup implements the scripting-**disabled** tree construction path, so
 *    a body-position `<noscript>`'s contents are parsed as markup rather than raw text; and it keeps
 *    `<template>` contents inline rather than in an inert fragment, and names a `<svg>`/`<math>` script
 *    `script` in any namespace (an `SVGScriptElement` loads `href`, never `src`; MathML has no script).
 *    [wouldExecute] requires the element to be an HTML-namespace script with no inert ancestor.
 *
 * A site that lets untrusted users inject a live `<script>` *element* into its homepage can still be
 * forged. That is accepted and recorded in ADR-17: such a site is already fully compromised, and no
 * verification scheme survives it. The bar this class restores is that forgery requires exactly that —
 * not merely getting a raw `<` into any inert corner of the page.
 *
 * Every divergence above has a named reproduction in [SnippetMatcherForgeryTest]. Anything that only
 * makes us reject a page a browser would have accepted is a support ticket, not a breach, so this class
 * resolves every doubt that way.
 */
object SnippetMatcher {
    private const val SITE_KEY_ATTRIBUTE = "data-cookiekeeper"
    private const val SRC_ATTRIBUTE = "src"

    /**
     * Stands in for a character jsoup and a browser would tokenize differently. U+FFFD is the right
     * choice because HTML gives it no syntactic meaning in any state: it cannot open, close, split or
     * terminate a tag, an attribute or a name, so substituting it can only ever *lengthen* a name.
     */
    private const val SENTINEL = '\uFFFD'

    /** Elements whose descendants a browser parses but never executes. See [wouldExecute]. */
    private val INERT_ANCESTORS = setOf("template", "noscript")

    /** HTML's whitespace set, which is narrower than both [Char.isWhitespace] and [String.trim]. */
    private val HTML_WHITESPACE = setOf('\t', '\n', '\u000C', '\r', ' ')

    /**
     * The characters [alignTokenizerWithBrowsers] neutralizes — the ones where jsoup's Java-flavoured
     * name handling accepts something an HTML tokenizer would not:
     *
     *  - **C0 controls that are not HTML whitespace.** jsoup normalizes tag and attribute names with
     *    `String.trim()`, which strips everything `<= U+0020`. HTML strips exactly TAB/LF/FF/CR/SPACE and
     *    keeps the rest *in the name*, so `<script …>` is an unknown element to a browser and a
     *    live script to jsoup.
     *  - **Non-ASCII characters Java's case folding maps onto an ASCII letter.** `equalsIgnoreCase` —
     *    which jsoup uses both for attribute lookup and to decide whether an end tag closes a raw-text
     *    element — accepts `scrİpt` as `script`, while HTML folds ASCII only. Computed rather than
     *    enumerated so that a JDK which widens its fold tables cannot silently reopen the hole.
     */
    private val DIVERGENT_CHARS: Set<Char> =
        buildSet {
            addAll((0..LAST_C0_CONTROL).map(Int::toChar).filterNot { it in HTML_WHITESPACE })
            addAll((FIRST_NON_ASCII..LAST_BMP).map(Int::toChar).filter { it.foldsOntoAsciiLetter() })
        }

    private const val LAST_C0_CONTROL = 0x1F
    private const val FIRST_NON_ASCII = 0x80
    private const val LAST_BMP = 0xFFFF

    /** Mirrors what [String.equalsIgnoreCase] does per character: try upper, then lower-of-upper. */
    private fun Char.foldsOntoAsciiLetter(): Boolean =
        uppercaseChar() in 'A'..'Z' || lowercaseChar() in 'a'..'z' || uppercaseChar().lowercaseChar() in 'a'..'z'

    /**
     * True when [html] contains a script element that a browser would load from [cdnHost] and that
     * carries exactly [siteKey].
     *
     * [cdnHost] is the *host* of `cookiekeeper.cdn-base-url` — compared host-to-host so a CDN path or scheme
     * change doesn't invalidate every customer's installed snippet, while `evil.com/cdn.cookiekeeper.eu/v1.js`
     * still fails. An explicit port is deliberately not compared: reaching a wrong-port URL requires
     * placing a script element in the first place, which is already the accepted total-compromise case.
     */
    fun matches(
        html: String,
        siteKey: String,
        cdnHost: String,
    ): Boolean {
        if (siteKey.isEmpty() || cdnHost.isEmpty()) return false
        val expectedHost = cdnHost.lowercase()
        // No base URI: a relative src must stay unresolvable rather than silently inherit a host.
        return Jsoup
            .parse(alignTokenizerWithBrowsers(html))
            .select("script")
            .any { element ->
                // Both attributes on one element: the pair is the proof, neither half means anything alone.
                element.wouldExecute() &&
                    element.exactAttribute(SITE_KEY_ATTRIBUTE) == siteKey &&
                    srcHost(element.exactAttribute(SRC_ATTRIBUTE).orEmpty()) == expectedHost
            }
    }

    /**
     * Replaces every character that would make jsoup's name handling disagree with an HTML tokenizer
     * with [SENTINEL], so the two agree on which tags and attributes exist.
     *
     * This runs over the whole document rather than over names alone because names are only knowable
     * *after* tokenizing — which is precisely the step being corrected. That is safe: [SENTINEL] is not
     * `<`, `>`, `/`, `=`, a quote or whitespace, so it can never create, split or terminate a token. It
     * can only lengthen a name or a run of text, which can only cost us a match. See [DIVERGENT_CHARS]
     * for what is replaced and why.
     */
    private fun alignTokenizerWithBrowsers(html: String): String =
        if (html.none { it in DIVERGENT_CHARS }) {
            // The overwhelmingly common case for a real homepage, and worth not copying half a megabyte for.
            html
        } else {
            buildString(html.length) { html.forEach { append(if (it in DIVERGENT_CHARS) SENTINEL else it) } }
        }

    /**
     * True when a browser would actually run this element, as opposed to merely having parsed it.
     *
     * jsoup places scripts in three positions where browsers never execute them, and every one of them
     * is reachable by a sanitizer that permits a container element while stripping bare `<script>`:
     *  - `<template>` — contents belong to an inert document fragment; nothing in them ever runs.
     *  - `<noscript>` — jsoup implements the scripting-*disabled* path and parses the contents as markup;
     *    a browser with scripting on treats the whole thing as raw text.
     *  - `<svg>`/`<math>` — foreign content. jsoup's `normalName()` is `script` in every namespace, but an
     *    `SVGScriptElement` takes its URL from `href`/`xlink:href`, and MathML has no script element at
     *    all, so nothing here can ever load our `src`. The namespace test also correctly *keeps* the
     *    HTML integration points (`<svg><title>`, `<foreignObject>`), where a browser does execute.
     */
    private fun Element.wouldExecute(): Boolean =
        tag().namespace() == Parser.NamespaceHtml && parents().none { it.normalName() in INERT_ANCESTORS }

    /**
     * The value of the attribute named exactly [name], or null.
     *
     * Deliberately not `Element.attr`: that resolves the name with [String.equalsIgnoreCase], and Java's
     * case folding is wider than HTML's ASCII-only rule — `Character.toUpperCase('ſ')` is `'S'`, so
     * jsoup reports `<script ſrc=…>` as carrying a `src` that no browser would fetch. jsoup already
     * ASCII-lowercases genuine attribute names while parsing, so an exact comparison still accepts `SRC`
     * and `DATA-COMPLYR` while rejecting the lookalikes. Duplicate names keep the first, as browsers do.
     */
    private fun Element.exactAttribute(name: String): String? = attributes().firstOrNull { it.key == name }?.value

    /**
     * The host a browser would actually fetch the `src` from, lowercased, or null when it would not be
     * our CDN. Only `http`, `https` and protocol-relative (`//host/…`) are accepted — `javascript:` and
     * `data:` URLs can embed `//cdn.cookiekeeper.eu/` in their opaque body while loading nothing at all.
     */
    @Suppress("ReturnCount") // each return is a distinct fail-closed condition; folding them hides that
    private fun srcHost(src: String): String? {
        // Browsers strip TAB/LF/CR from anywhere in a URL, and treat `\` as `/` in a special scheme's
        // authority. Normalizing both here keeps us aligned with what the browser resolves.
        val normalized =
            src
                .filterNot { it == '\t' || it == '\n' || it == '\r' }
                .replace('\\', '/')
                .trim()
                // The authority always ends before the first `?` or `#`, so dropping them cannot change the
                // host — but it does stop `java.net.URI`'s RFC 2396 grammar (stricter than WHATWG's about
                // `|`, `^`, `{}` and spaces) from rejecting a perfectly ordinary cache-busted snippet URL
                // outright, which would present to the customer as an unexplained verification failure.
                .substringBefore('#')
                .substringBefore('?')
        val uri =
            try {
                URI(normalized)
            } catch (_: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return null
        // A trailing root dot names the same host to DNS, and browsers resolve it as such.
        return uri.host?.lowercase()?.removeSuffix(".")
    }
}
