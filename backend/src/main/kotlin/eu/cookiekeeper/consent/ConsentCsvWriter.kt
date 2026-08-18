package eu.cookiekeeper.consent

import eu.cookiekeeper.common.CsvCell
import eu.cookiekeeper.consent.dto.ConsentEventLogResponse

/**
 * Serializes consent-log rows to RFC 4180 CSV. Cell escaping (formula-injection defence + RFC 4180 quoting)
 * lives in the shared [CsvCell] so every export path shares one implementation.
 *
 * Line terminator is CRLF per the spec. Category maps are emitted key-sorted (`k=v;…`) for deterministic output.
 */
object ConsentCsvWriter {
    private const val ROW_TERMINATOR = "\r\n"
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

    /** Delegates to the shared [CsvCell.escape]; retained for the injection-vector unit tests. */
    fun escapeCell(raw: String): String = CsvCell.escape(raw)
}
