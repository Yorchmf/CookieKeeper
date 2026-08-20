package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.BannerConfigCopyRequest
import eu.cookiekeeper.banner.dto.BannerConfigCopyResponse
import eu.cookiekeeper.banner.dto.BannerConfigResponse
import eu.cookiekeeper.banner.dto.BannerConfigUpdateRequest
import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Authenticated banner-config management, nested under the owning site so the path asserts the
 * ownership scope (JWT-authenticated like the rest of `/api/v1/sites`). GET reads the current
 * published config; PUT validates and publishes a new version. The public, cacheable read the widget
 * consumes lives separately in [WidgetConfigController].
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/banner-config")
class BannerConfigController(
    private val bannerConfigService: BannerConfigService,
) {
    @GetMapping
    fun get(
        @PathVariable siteId: UUID,
    ): ApiResponse<BannerConfigResponse> = ApiResponse.success(bannerConfigService.getForOwner(CurrentUser.id(), siteId))

    @PutMapping
    fun update(
        @PathVariable siteId: UUID,
        @Valid @RequestBody request: BannerConfigUpdateRequest,
    ): ApiResponse<BannerConfigResponse> = ApiResponse.success(bannerConfigService.update(CurrentUser.id(), siteId, request))

    /**
     * Apply this site's published banner to other sites the account owns. A POST, not a PUT: it is not
     * idempotent — each call appends a new version to every target. Carries only site ids; the document
     * comes from the source server-side (see [BannerConfigService.copyToSites]). Throttled by its own
     * tight tier, because one request fans out into up to one version write per owned site.
     */
    @PostMapping("/copy")
    fun copy(
        @PathVariable siteId: UUID,
        @Valid @RequestBody request: BannerConfigCopyRequest,
    ): ApiResponse<BannerConfigCopyResponse> =
        ApiResponse.success(bannerConfigService.copyToSites(CurrentUser.id(), siteId, request.targetSiteIds))
}
