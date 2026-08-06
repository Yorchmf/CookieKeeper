package com.complyr.policy

import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import com.complyr.policy.dto.PolicyCurrentResponse
import com.complyr.policy.dto.PolicyGenerationRequest
import com.complyr.policy.dto.PolicyGenerationResponse
import com.complyr.policy.dto.PublicPolicyResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Authenticated policy management, nested under the owning site so the path itself asserts the
 * ownership scope (JWT-authenticated like the rest of `/api/v1/sites`). Generation is the only
 * write; the public hosted read lives in [PublicPolicyController].
 */
@RestController
@RequestMapping("/api/v1/sites/{siteId}/policy")
class PolicyController(
    private val policyService: PolicyService,
) {
    @PostMapping
    fun generate(
        @PathVariable siteId: UUID,
        @Valid @RequestBody request: PolicyGenerationRequest,
    ): ApiResponse<PolicyGenerationResponse> = ApiResponse.success(policyService.generate(CurrentUser.id(), siteId, request))

    @GetMapping
    fun current(
        @PathVariable siteId: UUID,
    ): ApiResponse<PolicyCurrentResponse> = ApiResponse.success(policyService.current(CurrentUser.id(), siteId))

    /**
     * The owner's preview of the rendered policy. Returns [PublicPolicyResponse] verbatim — the same
     * payload the hosted page serves — so the dashboard renders the preview and the live page from one
     * shape. Reached by site id behind the JWT because the hosted read is gated on domain verification
     * and the preview is not; see [PolicyService.preview].
     */
    @GetMapping("/preview")
    fun preview(
        @PathVariable siteId: UUID,
        @RequestParam(name = "lang", required = false) language: String?,
    ): ApiResponse<PublicPolicyResponse> = ApiResponse.success(policyService.preview(CurrentUser.id(), siteId, language))
}
