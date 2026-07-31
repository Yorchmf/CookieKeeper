package com.complyr.scan

import com.complyr.common.ApiMeta
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import com.complyr.scan.dto.ScanDetailResponse
import com.complyr.scan.dto.ScanSummaryResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Scan-results read API, nested under the owning site so the path itself asserts the ownership scope
 * (JWT-authenticated like the rest of `/api/v1/sites`). Write side (enqueue/crawl/classify) lives in
 * the scanner worker; this controller is read-only.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/scans")
class ScanController(
    private val scanQueryService: ScanQueryService,
) {
    @GetMapping
    fun list(
        @PathVariable siteId: UUID,
        @RequestParam(defaultValue = "${ScanQueryService.DEFAULT_LIMIT}") limit: Int,
    ): ApiResponse<List<ScanSummaryResponse>> {
        val scans = scanQueryService.list(CurrentUser.id(), siteId, limit)
        return ApiResponse.success(scans, meta = ApiMeta(total = scans.size.toLong()))
    }

    @GetMapping("/{scanId}")
    fun get(
        @PathVariable siteId: UUID,
        @PathVariable scanId: UUID,
    ): ApiResponse<ScanDetailResponse> = ApiResponse.success(scanQueryService.get(CurrentUser.id(), siteId, scanId))
}
