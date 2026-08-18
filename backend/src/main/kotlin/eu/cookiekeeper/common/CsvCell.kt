package eu.cookiekeeper.common

/**
 * RFC 4180 cell serialization with CSV-injection defence, shared by every CSV export path (consent log,
 * analytics). Two independent concerns per cell:
 *
 *  1. **Formula injection** — a spreadsheet treats a cell whose first character is `= + - @` (or a leading
 *     tab / CR) as a formula. We prefix such a cell with a single quote so Excel/Sheets/LibreOffice render the
 *     literal text instead of executing it. Applied to the raw value first.
 *  2. **RFC 4180 quoting** — a cell containing a comma, double-quote, CR or LF is wrapped in double quotes with
 *     internal quotes doubled, so the delimiter/record structure can't be broken by field content.
 *
 * Kept in one place so a fix to the escaping rules can never apply to one export but not another.
 */
object CsvCell {
    private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')
    private val QUOTE_TRIGGERS = setOf(',', '"', '\n', '\r')

    /**
     * Neutralize a formula trigger (prefix `'`), then RFC 4180-quote if the result carries a structural
     * character. Order matters: the `'` is added before the quote test so an all-safe `=1+1` still quotes only
     * when it must.
     */
    fun escape(raw: String): String {
        val neutralized = if (raw.isNotEmpty() && raw[0] in FORMULA_TRIGGERS) "'$raw" else raw
        return if (neutralized.any { it in QUOTE_TRIGGERS }) {
            "\"${neutralized.replace("\"", "\"\"")}\""
        } else {
            neutralized
        }
    }
}
