package com.complyr.consent

import com.complyr.common.ApiResponse
import com.complyr.consent.dto.ConsentAcceptedResponse
import com.complyr.consent.dto.ConsentEventRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated, CORS-open consent ingestion. Rate-limited per client IP by
 * [com.complyr.common.RateLimitFilter]. Network metadata (IP, User-Agent) is read from the
 * request here — never from the JSON body — and handed to the service for one-way hashing
 * and trimming before it ever touches the append-only audit log.
 */
@RestController
@RequestMapping("/api/v1/consent")
class ConsentController(
    private val consentService: ConsentService,
) {
    @PostMapping
    fun record(
        @Valid @RequestBody request: ConsentEventRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<ConsentAcceptedResponse> {
        // remoteAddr is the real client IP behind Caddy (server.forward-headers-strategy: native).
        val meta =
            ConsentRequestMeta(
                clientIp = httpRequest.remoteAddr,
                userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT),
                // Verified against the origin bound into the token (when one is present); the browser
                // sets this and can't forge it cross-origin. Null for same-origin / non-browser callers.
                origin = httpRequest.getHeader(HttpHeaders.ORIGIN),
            )
        consentService.record(request, meta)
        return ApiResponse.success(ConsentAcceptedResponse())
    }
}
