package eu.cookiekeeper.consent

import eu.cookiekeeper.common.ApiMeta
import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import eu.cookiekeeper.consent.dto.ConsentEventLogResponse
import eu.cookiekeeper.consent.dto.ConsentLogFilter
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID

/**
 * Consent audit-log read API, nested under the owning site so the path asserts the ownership scope
 * (JWT-authenticated). Keyset-paginated newest-first; the next page is fetched with the `cursor` echoed in
 * `meta.nextCursor`. Filters (date range, action, lang, visitor) are all optional and narrow the result set.
 * The consent log is append-only audit evidence — this controller is strictly read-only.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/consent-events")
class ConsentLogController(
    private val consentLogService: ConsentLogService,
    private val csvExportService: ConsentCsvExportService,
) {
    @GetMapping
    fun list(
        @PathVariable siteId: UUID,
        filter: ConsentLogFilter,
    ): ApiResponse<List<ConsentEventLogResponse>> {
        val page = consentLogService.list(CurrentUser.id(), siteId, filter)
        return ApiResponse.success(page.events, meta = ApiMeta(nextCursor = page.nextCursor))
    }

    /**
     * Business-plan CSV export of the same filtered log. Entitlement (403) and ownership (404) are resolved
     * eagerly before the streaming body is returned, so a denial produces the normal JSON error envelope rather
     * than a truncated 200. The body then streams keyset batches, keeping memory bounded for large histories.
     */
    @GetMapping("/export.csv", produces = ["text/csv"])
    fun export(
        @PathVariable siteId: UUID,
        filter: ConsentLogFilter,
    ): ResponseEntity<StreamingResponseBody> {
        val userId = CurrentUser.id()
        csvExportService.authorize(userId, siteId)
        val body =
            StreamingResponseBody { out ->
                out.bufferedWriter(Charsets.UTF_8).use { csvExportService.writeCsv(userId, siteId, filter, it) }
            }
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"consent-events.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(body)
    }
}
