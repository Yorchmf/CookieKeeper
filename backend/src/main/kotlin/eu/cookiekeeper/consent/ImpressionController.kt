package eu.cookiekeeper.consent

import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.consent.dto.ImpressionAcceptedResponse
import eu.cookiekeeper.consent.dto.ImpressionRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, unauthenticated, CORS-open banner-impression beacon (Track 4 Slice D). Rate-limited per client IP on
 * the IMPRESSION tier by [eu.cookiekeeper.common.RateLimitFilter].
 *
 * Unlike [ConsentController] it reads NO request metadata — not even the IP — because the impression counter
 * stores no personal data (CLAUDE.md #4): the beacon carries only the site key, and the server stamps the day.
 * The IP is consumed solely as the rate-limit bucket key in the filter, never here.
 */
@RestController
@RequestMapping("/api/v1/impression")
class ImpressionController(
    private val impressionService: ImpressionService,
) {
    @PostMapping
    fun record(
        @Valid @RequestBody request: ImpressionRequest,
    ): ApiResponse<ImpressionAcceptedResponse> {
        impressionService.record(request)
        return ApiResponse.success(ImpressionAcceptedResponse())
    }
}
