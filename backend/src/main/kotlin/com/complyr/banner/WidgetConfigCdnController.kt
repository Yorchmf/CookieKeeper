package com.complyr.banner

import com.complyr.banner.dto.WidgetConfigPayload
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * The URL the embedded widget actually fetches: `GET /cfg/{siteKey}.json` (ADR-19).
 *
 * It sits on the CDN host — Caddy's `cdn.` vhost proxies the `/cfg/` prefix here while everything
 * else on that host is still served from disk — so the widget's single derived base URL covers both the
 * bundle and the config, and Cloudflare fronts the read. That is why the path is NOT under
 * `/api/v1`: it is a CDN asset URL, and the `{ success, data, error, meta }` envelope every
 * `/api/v1` endpoint returns stays literally true. The widget parses the config object directly
 * ([WidgetConfigPayload]), so an envelope here would make every published config unusable.
 *
 * [WidgetConfigController] keeps serving the enveloped `/api/v1/widget-config/{siteKey}` for
 * API consumers and for the dashboard preview; both read the same published config.
 *
 * Unauthenticated, CORS-open and cacheable for 5 minutes, exactly like that endpoint — a config
 * only changes when the customer republishes.
 */
@RestController
class WidgetConfigCdnController(
    private val bannerConfigService: BannerConfigService,
) {
    // The site key is constrained in the pattern (it is `pk_` + 32 alphanumerics), so a request
    // with a dotted or encoded key 404s at routing instead of reaching the service.
    @GetMapping("/cfg/{siteKey:[A-Za-z0-9_-]{1,64}}.json")
    fun get(
        @PathVariable siteKey: String,
    ): ResponseEntity<WidgetConfigPayload> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(CONFIG_CACHE_MAX_AGE).cachePublic())
            .body(WidgetConfigMapper.toPayload(bannerConfigService.widgetConfig(siteKey)))

    private companion object {
        val CONFIG_CACHE_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
