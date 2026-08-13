package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.SiteAnalyticsResponse
import com.complyr.billing.EntitlementService
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Customer-facing site analytics, nested under the owning site so the path asserts the ownership scope
 * (JWT-authenticated). All figures are aggregated from our own data (`consent_events`, `scan_cookies`/`scans`,
 * `policies`) — never third-party telemetry. The optional `from`/`to` query params bound the window; the
 * service defaults to the trailing 30 days.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/analytics")
class AnalyticsController(
    private val analyticsService: AnalyticsService,
    private val entitlementService: EntitlementService,
) {
    @GetMapping
    fun summarize(
        @PathVariable siteId: UUID,
        filter: AnalyticsFilter,
    ): ApiResponse<SiteAnalyticsResponse> = ApiResponse.success(analyticsService.summarize(CurrentUser.id(), siteId, filter))

    /**
     * Business-plan CSV export of the consent trend. Entitlement (403) is checked before ownership (404), and
     * both resolve before the body is built, so a denial produces the normal JSON error envelope. The payload is
     * bounded (one row per day of the window), so it is assembled in memory rather than streamed.
     */
    @GetMapping("/export.csv", produces = ["text/csv"])
    fun export(
        @PathVariable siteId: UUID,
        filter: AnalyticsFilter,
    ): ResponseEntity<ByteArray> {
        val userId = CurrentUser.id()
        entitlementService.requireCsvExport(userId)
        val trend = analyticsService.consentTrend(userId, siteId, filter)
        val csv =
            buildString {
                append(AnalyticsCsvWriter.header())
                trend.forEach { append(AnalyticsCsvWriter.row(it)) }
            }
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analytics-trend.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv.toByteArray(Charsets.UTF_8))
    }
}
