package eu.cookiekeeper.scan

import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.scan.dto.PublicScanCreatedResponse
import eu.cookiekeeper.scan.dto.PublicScanReportRequest
import eu.cookiekeeper.scan.dto.PublicScanReportResponse
import eu.cookiekeeper.scan.dto.PublicScanRequest
import eu.cookiekeeper.scan.dto.PublicScanTeaserResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated marketing-funnel endpoint: request an anonymous free scan of a domain
 * (docs ADR-12). Permitted in [eu.cookiekeeper.common.SecurityConfig] and rate-limited per client IP by
 * the [eu.cookiekeeper.common.RateLimitFilter] `PUBLIC_SCAN` tier; the honeypot, ip_hash capture, and
 * concurrency cap live in [PublicScanService].
 *
 * The source IP is read here from the request (never the JSON body) and handed to the service for
 * one-way hashing — the raw IP is never persisted or logged (CLAUDE.md #4), mirroring consent ingestion.
 *
 * SSRF note: this endpoint hands a *visitor-supplied* domain to the scanner. It is safe because the
 * domain is only enqueued here — the load-bearing defense ([ScanTargetValidator] resolve-public
 * pre-flight + per-request guards + scanner network isolation) runs later inside the crawl engine, not
 * on this request thread.
 *
 * Read side ([PublicScanReadService]): the result is addressed only by its opaque token, never by an
 * owner. [teaser] is free (counts only); [report] captures the visitor's email to unlock the detail.
 * An unknown/expired/honeypot token yields one identical 404 so the honeypot is not a detection oracle.
 */
@RestController
@RequestMapping("/api/v1/public-scan")
class PublicScanController(
    private val publicScanService: PublicScanService,
    private val publicScanReadService: PublicScanReadService,
) {
    @PostMapping
    fun request(
        @Valid @RequestBody request: PublicScanRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<PublicScanCreatedResponse> {
        // remoteAddr is the real client IP behind Caddy (server.forward-headers-strategy: native).
        return ApiResponse.success(publicScanService.request(request, httpRequest.remoteAddr))
    }

    @GetMapping("/{token}")
    fun teaser(
        @PathVariable token: String,
    ): ApiResponse<PublicScanTeaserResponse> = ApiResponse.success(publicScanReadService.teaser(token))

    @PostMapping("/{token}/report")
    fun report(
        @PathVariable token: String,
        @Valid @RequestBody request: PublicScanReportRequest,
    ): ApiResponse<PublicScanReportResponse> = ApiResponse.success(publicScanReadService.unlockReport(token, request))
}
