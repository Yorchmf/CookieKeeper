package eu.cookiekeeper.analytics

import eu.cookiekeeper.analytics.dto.ConsentTrendPoint
import eu.cookiekeeper.common.CsvCell

/**
 * Serializes the consent trend (one row per UTC day) to RFC 4180 CSV — the Business-plan analytics export.
 * Cell escaping (formula-injection defence + quoting) is the shared [CsvCell]. CRLF row terminator per spec.
 */
object AnalyticsCsvWriter {
    private const val ROW_TERMINATOR = "\r\n"
    private val HEADER = listOf("date", "accept_all", "reject_all", "custom", "total")

    fun header(): String = HEADER.joinToString(",", postfix = ROW_TERMINATOR, transform = CsvCell::escape)

    fun row(point: ConsentTrendPoint): String =
        listOf(
            point.date.toString(),
            point.acceptAll.toString(),
            point.rejectAll.toString(),
            point.custom.toString(),
            point.total.toString(),
        ).joinToString(",", postfix = ROW_TERMINATOR, transform = CsvCell::escape)
}
