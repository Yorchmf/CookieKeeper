package com.complyr.policy

/**
 * Minimal, allocation-conscious HTML text/attribute escaper for the policy generator.
 *
 * The policy is assembled by concatenating trusted template strings with untrusted dynamic values
 * (company name, contact email, website URL, and — crucially — cookie names/providers/domains that
 * originate from a *scanned third-party page*, i.e. attacker-influenced). Every dynamic value passes
 * through here before it reaches the HTML, so the generated document can never carry script or markup
 * injected via those fields (CLAUDE.md #4: we are a GDPR product — the hosted policy page must be
 * exemplary). All rendered values live in element-text context; escaping the five significant
 * characters (incl. both quote styles) also makes the output safe if a value ever moves into a
 * double- or single-quoted attribute.
 */
object HtmlEscape {
    fun escape(value: String): String =
        buildString(value.length + ESCAPE_HEADROOM) {
            for (char in value) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }

    private const val ESCAPE_HEADROOM = 16
}
