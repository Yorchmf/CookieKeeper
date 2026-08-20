package eu.cookiekeeper.policy

import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.policy.dto.PublicCookieTableResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * Public, unauthenticated read behind the embeddable cookie table (ADR-27): the widget fetches this
 * when it finds a `<div data-complyr-policy>` on the page it is embedded in, so a customer's own
 * lawyer-approved cookie policy stays in sync with the latest scan without them editing it.
 *
 * Addressed by the public site key — the same identifier already sitting in the embed snippet on that
 * page, at the same trust level as `GET /cfg/{siteKey}.json`, so the feature needs no new customer
 * configuration. Permitted in [eu.cookiekeeper.common.SecurityConfig], CORS-open (it is read from the
 * customer's origin) and rate-limited on the `PUBLIC_POLICY` tier.
 *
 * Unlike `/cfg/`, this one keeps the `/api/v1` envelope: it is not fetched on every page load — only
 * on the handful of pages that embed the table — so it stays an ordinary API read rather than a CDN
 * asset. It is still cacheable for 5 minutes; the list only changes when a scan completes.
 */
@RestController
@RequestMapping("/api/v1/public/cookie-table")
class PublicCookieTableController(
    private val cookieTableReadService: CookieTableReadService,
) {
    // The key shape is constrained in the pattern (`pk_` + alphanumerics), so a malformed key 404s at
    // routing instead of reaching the service.
    @GetMapping("/{siteKey:[A-Za-z0-9_-]{1,64}}")
    fun read(
        @PathVariable siteKey: String,
        @RequestParam(name = "lang", required = false) language: String?,
    ): ResponseEntity<ApiResponse<PublicCookieTableResponse>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(TABLE_CACHE_MAX_AGE).cachePublic())
            .body(ApiResponse.success(cookieTableReadService.read(siteKey, language)))

    private companion object {
        val TABLE_CACHE_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
