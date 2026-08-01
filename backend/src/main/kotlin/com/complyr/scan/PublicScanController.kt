package com.complyr.scan

import com.complyr.common.ApiResponse
import com.complyr.scan.dto.PublicScanCreatedResponse
import com.complyr.scan.dto.PublicScanRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated marketing-funnel endpoint: request an anonymous free scan of a domain
 * (docs ADR-12). Permitted in [com.complyr.common.SecurityConfig]; abuse controls (per-IP rate-limit
 * tier, honeypot, ip_hash capture, concurrent-scan cap) land in slice D.
 *
 * SSRF note: this endpoint hands a *visitor-supplied* domain to the scanner. It is safe because the
 * domain is only enqueued here — the load-bearing defense ([ScanTargetValidator] resolve-public
 * pre-flight + per-request guards + scanner network isolation) runs later inside the crawl engine, not
 * on this request thread.
 */
@RestController
@RequestMapping("/api/v1/public-scan")
class PublicScanController(
    private val publicScanService: PublicScanService,
) {
    @PostMapping
    fun request(
        @Valid @RequestBody request: PublicScanRequest,
    ): ApiResponse<PublicScanCreatedResponse> = ApiResponse.success(publicScanService.request(request))
}
