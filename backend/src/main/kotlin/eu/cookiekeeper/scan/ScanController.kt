package eu.cookiekeeper.scan

import eu.cookiekeeper.common.ApiMeta
import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import eu.cookiekeeper.scan.dto.ScanDetailResponse
import eu.cookiekeeper.scan.dto.ScanRequestedResponse
import eu.cookiekeeper.scan.dto.ScanSummaryResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Scan API, nested under the owning site so the path itself asserts the ownership scope
 * (JWT-authenticated like the rest of `/api/v1/sites`). Reads return a site's history and one scan's
 * classified cookies; the single write is "re-scan now", which only enqueues — the crawl and
 * classification themselves run in the scanner worker.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/scans")
class ScanController(
    private val scanQueryService: ScanQueryService,
    private val scanRequestService: ScanRequestService,
) {
    @GetMapping
    fun list(
        @PathVariable siteId: UUID,
        @RequestParam(defaultValue = "${ScanQueryService.DEFAULT_LIMIT}") limit: Int,
    ): ApiResponse<List<ScanSummaryResponse>> {
        val scans = scanQueryService.list(CurrentUser.id(), siteId, limit)
        return ApiResponse.success(scans, meta = ApiMeta(total = scans.size.toLong()))
    }

    /**
     * Queue an immediate re-scan (Pro and Business — [eu.cookiekeeper.billing.EntitlementService.requireOnDemandRescan]).
     * 201 because it creates a scan resource, not because the crawl finished: the response is an
     * acknowledgement the dashboard follows with its existing scan-list poll. 409 when the site already has
     * a live scan, which is the throttle — see [ScanRequestService].
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun request(
        @PathVariable siteId: UUID,
    ): ApiResponse<ScanRequestedResponse> {
        val scanId = scanRequestService.request(CurrentUser.id(), siteId)
        return ApiResponse.success(ScanRequestedResponse(scanId))
    }

    @GetMapping("/{scanId}")
    fun get(
        @PathVariable siteId: UUID,
        @PathVariable scanId: UUID,
    ): ApiResponse<ScanDetailResponse> = ApiResponse.success(scanQueryService.get(CurrentUser.id(), siteId, scanId))
}
