package com.complyr.consent

import com.complyr.consent.dto.ConsentEventLogResponse

/**
 * Serializes consent-log rows to RFC 4180 CSV with CSV-injection defence. Two independent concerns per cell:
 *
 *  1. **Formula injection** — a spreadsheet treats a cell whose first character is `= + - @` (or a leading
 *     tab / CR) as a formula. We prefix such a cell with a single quote so Excel/Sheets/LibreOffice render the
 *     literal text instead of executing it. Applied to the raw value first.
 *  2. **RFC 4180 quoting** — a cell containing a comma, double-quote, CR or LF is wrapped in double quotes with
 *     internal quotes doubled, so the delimiter/record structure can't be broken by field content.
 *
 * Line terminator is CRLF per the spec. Category maps are emitted key-sorted (`k=v;…`) for deterministic output.
 */
object ConsentCsvWriter {
    private const val ROW_TERMINATOR = "\r\n"
    private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')
    private val QUOTE_TRIGGERS = setOf(',', '"', '\n', '\r')
    private val HEADER =
        listOf(
            "created_at",
            "event_id",
            "visitor_id",
            "action",
            "lang",
            "banner_version",
            "policy_version",
            "categories",
        )

    fun header(): String = HEADER.joinToString(",", postfix = ROW_TERMINATOR, transform = ::escapeCell)

    fun row(event: ConsentEventLogResponse): String =
        listOf(
            event.createdAt.toString(),
            event.eventId.toString(),
            event.visitorId.toString(),
            event.action,
            event.lang.orEmpty(),
            event.bannerVersion?.toString().orEmpty(),
            event.policyVersion?.toString().orEmpty(),
            formatCategories(event.categories),
        ).joinToString(",", postfix = ROW_TERMINATOR, transform = ::escapeCell)

    private fun formatCategories(categories: Map<String, Boolean>): String =
        categories.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /**
     * Neutralize a formula trigger (prefix `'`), then RFC 4180-quote if the result carries a structural
     * character. Order matters: the `'` is added before the quote test so an all-safe `=1+1` still quotes only
     * when it must. Visible for direct unit testing of the injection vectors.
     */
    fun escapeCell(raw: String): String {
        val neutralized = if (raw.isNotEmpty() && raw[0] in FORMULA_TRIGGERS) "'$raw" else raw
        return if (neutralized.any { it in QUOTE_TRIGGERS }) {
            "\"${neutralized.replace("\"", "\"\"")}\""
        } else {
            neutralized
        }
    }
}
