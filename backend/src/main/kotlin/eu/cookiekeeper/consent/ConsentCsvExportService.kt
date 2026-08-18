package eu.cookiekeeper.consent

import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.consent.dto.ConsentLogFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.Writer
import java.util.UUID

/**
 * Streams a site's consent log as CSV (Business-plan feature). Reuses [ConsentLogService.list] for the keyset
 * walk, so the JSON read and the export share one query/DTO/PII-exclusion path — the export can never surface a
 * column the API hides. Serialization + CSV-injection defence live in [ConsentCsvWriter].
 *
 * [exportBatch] is the keyset page size for the walk; injected (default [DEFAULT_EXPORT_BATCH]) so a test can
 * shrink it to exercise real multi-page cursor advancement without seeding hundreds of rows.
 */
@Service
class ConsentCsvExportService(
    private val entitlementService: EntitlementService,
    private val consentLogService: ConsentLogService,
    @Value("\${cookiekeeper.consent.export-batch-size:$DEFAULT_EXPORT_BATCH}")
    private val exportBatch: Int = DEFAULT_EXPORT_BATCH,
) {
    /**
     * Both gates — entitlement (403) then ownership (404) — resolved eagerly so the controller can fail with a
     * proper JSON error envelope *before* it hands back a streaming body. Once the first CSV byte flushes, the
     * HTTP status is fixed, so these checks cannot live inside [writeCsv].
     */
    fun authorize(
        userId: UUID,
        siteId: UUID,
    ) {
        entitlementService.requireCsvExport(userId)
        consentLogService.assertOwnership(userId, siteId)
    }

    /**
     * Write the entire filtered result set (from newest) as CSV, walking the keyset in [exportBatch]-sized
     * pages so heap stays bounded no matter how much history a site has. Each page is its own short read-only
     * transaction (via [ConsentLogService.list]) — the right shape for a StreamingResponseBody on the async
     * dispatch thread, where no single long-lived connection/cursor should be held open for the whole download.
     * Assumes [authorize] has already passed; any inbound page cursor is ignored so the export is the full set.
     */
    fun writeCsv(
        userId: UUID,
        siteId: UUID,
        filter: ConsentLogFilter,
        writer: Writer,
    ) {
        writer.write(ConsentCsvWriter.header())
        var cursor: String? = null
        do {
            val page = consentLogService.list(userId, siteId, filter.copy(cursor = cursor, limit = exportBatch))
            page.events.forEach { writer.write(ConsentCsvWriter.row(it)) }
            cursor = page.nextCursor
        } while (cursor != null)
        writer.flush()
    }

    companion object {
        const val DEFAULT_EXPORT_BATCH = 200
    }
}
