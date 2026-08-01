package com.complyr.policy

import com.complyr.banner.ConsentCategory

/**
 * Renders a [PolicyContext] into the self-contained HTML content block stored in `policies.html`.
 *
 * Deterministic and template-based, never LLM (ADR-10): the same inputs always produce byte-identical
 * output, which is what makes a *versioned legal document* auditable. The block is both the copyable
 * embed the customer pastes into their own site and the body the hosted `/p/{publicId}` page wraps.
 * Every dynamic value is routed through [HtmlEscape]; the surrounding markup and all wording come from
 * the trusted [PolicyStrings] bundle.
 */
object PolicyRenderer {
    fun render(
        language: String,
        context: PolicyContext,
    ): String {
        // Resolve the effective language once so the wording bundle and the `lang` attribute never
        // disagree: an unsupported code falls back to the default for both (the service only ever
        // passes supported codes — this is purely defensive).
        val effectiveLanguage = language.takeIf(PolicyLanguages::isSupported) ?: PolicyLanguages.DEFAULT
        val strings = PolicyStrings.forLanguage(effectiveLanguage)
        val langAttr = HtmlEscape.escape(effectiveLanguage)
        return buildString {
            append("<section class=\"cmplyr-policy\" lang=\"").append(langAttr).append("\">\n")
            append("<h1>").append(HtmlEscape.escape(strings.title)).append("</h1>\n")
            append("<p class=\"cmplyr-policy-updated\">")
                .append(HtmlEscape.escape(strings.updatedLabel))
                .append(": ")
                .append(HtmlEscape.escape(context.updatedOn.toString()))
                .append("</p>\n")
            append("<p>").append(introHtml(strings, context)).append("</p>\n")
            append("<p>").append(contactHtml(strings, context)).append("</p>\n")
            appendAddress(strings, context)
            appendCookieSections(strings, context)
            append("</section>")
        }
    }

    private fun introHtml(
        strings: PolicyStrings,
        context: PolicyContext,
    ): String =
        strings.intro.fillPlaceholders(
            mapOf(
                PolicyStrings.PLACEHOLDER_COMPANY to HtmlEscape.escape(context.companyName),
                PolicyStrings.PLACEHOLDER_WEBSITE to HtmlEscape.escape(context.websiteUrl),
            ),
        )

    private fun contactHtml(
        strings: PolicyStrings,
        context: PolicyContext,
    ): String =
        strings.contact.fillPlaceholders(
            mapOf(PolicyStrings.PLACEHOLDER_EMAIL to HtmlEscape.escape(context.contactEmail)),
        )

    /**
     * Substitute `{...}` placeholders in a single left-to-right pass over the trusted template. Chained
     * [String.replace] calls would let a substituted (escaped) value that happens to contain a literal
     * `{placeholder}` be re-substituted by a later call; one pass rules that out, keeping the rendered
     * legal text a deterministic function of its inputs.
     */
    private fun String.fillPlaceholders(values: Map<String, String>): String {
        val out = StringBuilder(length)
        var i = 0
        while (i < length) {
            val match = values.entries.firstOrNull { startsWith(it.key, i) }
            if (match != null) {
                out.append(match.value)
                i += match.key.length
            } else {
                out.append(this[i])
                i++
            }
        }
        return out.toString()
    }

    private fun StringBuilder.appendAddress(
        strings: PolicyStrings,
        context: PolicyContext,
    ) {
        val address = context.address?.takeIf { it.isNotBlank() } ?: return
        append("<p>")
            .append(HtmlEscape.escape(strings.addressLabel))
            .append(": ")
            .append(HtmlEscape.escape(address))
            .append("</p>\n")
    }

    private fun StringBuilder.appendCookieSections(
        strings: PolicyStrings,
        context: PolicyContext,
    ) {
        if (context.hasNoCookies()) {
            append("<p>").append(HtmlEscape.escape(strings.noCookies)).append("</p>\n")
            return
        }
        // Canonical category order (necessary → preferences → statistics → marketing); only render a
        // section when that category actually has cookies, then the unclassified bucket last.
        for (category in ConsentCategory.entries) {
            val cookies = context.cookiesByCategory[category.key].orEmpty()
            if (cookies.isNotEmpty()) appendSection(strings.category(category.key), cookies, strings)
        }
        if (context.unclassified.isNotEmpty()) appendSection(strings.other, context.unclassified, strings)
    }

    private fun StringBuilder.appendSection(
        heading: CategoryText,
        cookies: List<PolicyCookie>,
        strings: PolicyStrings,
    ) {
        append("<h2>").append(HtmlEscape.escape(heading.name)).append("</h2>\n")
        append("<p>").append(HtmlEscape.escape(heading.description)).append("</p>\n")
        append("<table class=\"cmplyr-policy-table\">\n")
        append("<thead><tr><th>")
            .append(HtmlEscape.escape(strings.colName))
            .append("</th><th>")
            .append(HtmlEscape.escape(strings.colProvider))
            .append("</th><th>")
            .append(HtmlEscape.escape(strings.colExpiry))
            .append("</th></tr></thead>\n")
        append("<tbody>\n")
        for (cookie in cookies) appendRow(cookie, strings)
        append("</tbody>\n</table>\n")
    }

    private fun StringBuilder.appendRow(
        cookie: PolicyCookie,
        strings: PolicyStrings,
    ) {
        val provider = cookie.provider?.takeIf { it.isNotBlank() } ?: cookie.domain?.takeIf { it.isNotBlank() } ?: strings.unknownProvider
        val expiry = cookie.expiry?.takeIf { it.isNotBlank() } ?: strings.sessionExpiry
        append("<tr><td>")
            .append(HtmlEscape.escape(cookie.name))
            .append("</td><td>")
            .append(HtmlEscape.escape(provider))
            .append("</td><td>")
            .append(HtmlEscape.escape(expiry))
            .append("</td></tr>\n")
    }
}
