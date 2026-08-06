package com.complyr.policy

import com.complyr.common.ApiResponse
import com.complyr.policy.dto.PublicPolicyResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Public, unauthenticated hosted-policy read (docs §4.5): the dashboard's `/p/{publicId}` page fetches
 * this to server-render the cookie policy, and customers can link it directly. Permitted in
 * [com.complyr.common.SecurityConfig] and rate-limited on the `PUBLIC_POLICY` tier; the result is a
 * cacheable GET (Cloudflare caches public policy pages — see docs §1).
 *
 * Addressed only by the opaque public id, never by site id; an unknown id, an unpublished site, an
 * archived one and one whose domain is not yet verified (ADR-17) all return the same 404
 * ([PolicyNotFoundException]) so the id is not an existence oracle. An invalid UUID is a plain 400 (the
 * page content is public, so enumeration parity does not matter for malformed input).
 *
 * The dashboard's own preview does NOT come through here — it is authenticated and ungated, at
 * `GET /api/v1/sites/{siteId}/policy/preview`, so the owner can see the page before verifying.
 */
@RestController
@RequestMapping("/api/v1/public/policy")
class PublicPolicyController(
    private val policyReadService: PolicyReadService,
) {
    @GetMapping("/{publicId}")
    fun read(
        @PathVariable publicId: UUID,
        @RequestParam(name = "lang", required = false) language: String?,
    ): ApiResponse<PublicPolicyResponse> = ApiResponse.success(policyReadService.read(publicId, language))
}
