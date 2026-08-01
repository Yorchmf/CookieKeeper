package com.complyr.banner

import com.complyr.banner.dto.BannerConfigResponse
import com.complyr.banner.dto.BannerConfigUpdateRequest
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
}
