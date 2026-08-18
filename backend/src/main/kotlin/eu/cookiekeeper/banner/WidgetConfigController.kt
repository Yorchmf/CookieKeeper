package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.WidgetConfigResponse
import eu.cookiekeeper.common.ApiResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * Public, unauthenticated, CORS-open widget configuration read consumed by the embedded banner.
 * Responses are cacheable (`Cache-Control: public, max-age=300`) so Cloudflare absorbs the read
 * load — the config only changes when the customer republishes (ARCHITECTURE.md §4.1).
 */
@RestController
@RequestMapping("/api/v1/widget-config")
class WidgetConfigController(
    private val bannerConfigService: BannerConfigService,
) {
    @GetMapping("/{siteKey}")
    fun get(
        @PathVariable siteKey: String,
    ): ResponseEntity<ApiResponse<WidgetConfigResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(CONFIG_CACHE_MAX_AGE).cachePublic())
            .body(ApiResponse.success(bannerConfigService.widgetConfig(siteKey)))

    private companion object {
        val CONFIG_CACHE_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
