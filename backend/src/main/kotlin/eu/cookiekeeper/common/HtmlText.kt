package eu.cookiekeeper.common

/**
 * Minimal HTML-text escaping for values interpolated into email bodies, shared by every composer that
 * renders customer- or visitor-supplied text into HTML (scan-complete emails today; lead-facing emails
 * and any future free-text sink tomorrow). The HTML sibling of [CsvCell]: kept in one place so a fix to
 * the escaping rules can never apply to one email but not another.
 *
 * Escapes the five characters that can break out of HTML text content or a double-quoted attribute value:
 * `& < > " '`. Deliberately NOT Spring's `HtmlUtils.htmlEscape`, which also converts every non-ASCII
 * character to a numeric entity — that would mangle the IDN domains and localized (DE/FR/ES/IT) copy this
 * product exists to serve. `&` is replaced first so the entities emitted for the other four are not
 * double-escaped.
 *
 * This escapes text destined for element content or a quoted attribute. It is NOT sufficient for values
 * placed in a URL, an unquoted attribute, inside `<script>`/`<style>`, or an event handler — those need
 * context-specific encoding and should not be built from untrusted input here.
 */
object HtmlText {
    fun escape(raw: String): String =
        raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
