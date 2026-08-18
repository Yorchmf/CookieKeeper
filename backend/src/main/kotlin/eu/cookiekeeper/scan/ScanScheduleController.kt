package eu.cookiekeeper.scan

import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import eu.cookiekeeper.scan.dto.ScanScheduleResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A site's re-scan schedule (JWT-authenticated, ownership-scoped like the rest of `/api/v1/sites`).
 *
 * Deliberately a sibling of `/scans` rather than `/scans/schedule`: the scan collection already routes
 * `GET /scans/{scanId}` on a UUID path variable, and a literal sub-path there would sit one refactor away
 * from an ambiguous mapping. The schedule is a property of the site, not a member of its scan history.
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/scan-schedule")
class ScanScheduleController(
    private val scanScheduleService: ScanScheduleService,
) {
    @GetMapping
    fun get(
        @PathVariable siteId: UUID,
    ): ApiResponse<ScanScheduleResponse> = ApiResponse.success(scanScheduleService.forSite(CurrentUser.id(), siteId))
}
