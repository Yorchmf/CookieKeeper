package eu.cookiekeeper.consent

import eu.cookiekeeper.consent.dto.ConsentEventLogResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CSV export is audit evidence a customer may open in a spreadsheet, so escaping is a security concern,
 * not cosmetics: a cell must never be interpretable as a formula, and field content must never break the
 * comma/row structure. These pin the injection-defence and RFC 4180 rules directly.
 */
class ConsentCsvWriterTest {
    @Test
    fun `prefixes a formula-triggering cell with a quote so a spreadsheet will not execute it`() {
        for (trigger in listOf("=cmd", "+1", "-1", "@ref", "\tx", "\rx")) {
            assertEquals("'$trigger", stripQuoting(ConsentCsvWriter.escapeCell(trigger)))
        }
    }

    @Test
    fun `leaves an ordinary cell untouched`() {
        assertEquals("accept_all", ConsentCsvWriter.escapeCell("accept_all"))
    }

    @Test
    fun `rfc4180-quotes a cell containing a comma or quote and doubles inner quotes`() {
        assertEquals("\"a,b\"", ConsentCsvWriter.escapeCell("a,b"))
        assertEquals("\"a\"\"b\"", ConsentCsvWriter.escapeCell("a\"b"))
    }

    @Test
    fun `neutralizes then rfc-quotes a cell that is both a formula trigger and structurally unsafe`() {
        // Order matters: the leading '=' is neutralized to '= first, then the comma forces RFC quoting of the
        // already-prefixed value. Quoting first would leave the '=' at index 0 and a spreadsheet would execute it.
        assertEquals("\"'=1,2\"", ConsentCsvWriter.escapeCell("=1,2"))
        // A CR mid-cell forces quoting even though the first char is not a trigger.
        assertEquals("\"a\rb\"", ConsentCsvWriter.escapeCell("a\rb"))
    }

    @Test
    fun `header row is fixed, comma-separated and CRLF-terminated`() {
        assertEquals(
            "created_at,event_id,visitor_id,action,lang,banner_version,policy_version,categories\r\n",
            ConsentCsvWriter.header(),
        )
    }

    @Test
    fun `row emits sorted categories and neutralizes a malicious category key`() {
        val row = ConsentCsvWriter.row(sampleEvent(categories = mapOf("=danger" to true, "statistics" to false)))

        // Categories are one cell; a leading '=' from the key must be neutralized. No comma/quote/newline is
        // present so RFC quoting does not apply — the '=' and ';' are structurally safe inside a single field.
        // Sorted so '=danger' precedes 'statistics'.
        assertTrue(row.contains(",'=danger=true;statistics=false\r\n"), "was: $row")
        assertTrue(row.endsWith("\r\n"))
    }

    private fun stripQuoting(cell: String): String =
        if (cell.startsWith("\"") && cell.endsWith("\"")) {
            cell.substring(1, cell.length - 1).replace("\"\"", "\"")
        } else {
            cell
        }

    private fun sampleEvent(categories: Map<String, Boolean>): ConsentEventLogResponse =
        ConsentEventLogResponse(
            eventId = UUID.randomUUID(),
            visitorId = UUID.randomUUID(),
            action = "custom",
            categories = categories,
            bannerVersion = 1,
            policyVersion = 2,
            lang = "en",
            createdAt = Instant.parse("2026-08-01T10:00:00Z"),
        )
}
