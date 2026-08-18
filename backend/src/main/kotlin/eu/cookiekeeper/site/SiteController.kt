package eu.cookiekeeper.site

import eu.cookiekeeper.common.ApiMeta
import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import eu.cookiekeeper.common.InvalidQueryParamException
import eu.cookiekeeper.site.dto.ArchiveResponse
import eu.cookiekeeper.site.dto.BrandingPreferenceRequest
import eu.cookiekeeper.site.dto.CreateSiteRequest
import eu.cookiekeeper.site.dto.SiteDetailResponse
import eu.cookiekeeper.site.dto.SiteResponse
import eu.cookiekeeper.site.dto.SiteVerificationResponse
import eu.cookiekeeper.site.dto.UpdateSiteRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/sites")
class SiteController(
    private val siteService: SiteService,
    private val siteVerificationService: SiteVerificationService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "active") status: String,
    ): ApiResponse<List<SiteResponse>> {
        val parsedStatus =
            SiteStatus.entries.firstOrNull { it.dbValue == status }
                ?: throw InvalidQueryParamException("status must be one of: active, archived")
        val sites = siteService.list(CurrentUser.id(), parsedStatus)
        return ApiResponse.success(sites, meta = ApiMeta(total = sites.size.toLong()))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateSiteRequest,
    ): ApiResponse<SiteResponse> = ApiResponse.success(siteService.create(CurrentUser.id(), request.domain))

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ApiResponse<SiteDetailResponse> = ApiResponse.success(siteService.get(CurrentUser.id(), id))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateSiteRequest,
    ): ApiResponse<SiteDetailResponse> = ApiResponse.success(siteService.update(CurrentUser.id(), id, request.domain))

    /**
     * Set whether this site hides the "Powered by CookieKeeper" credit. Only the customer's *preference* —
     * it activates only if the plan also grants branding removal, floored server-side, so this endpoint
     * can never hand out the paid feature. Separate from [update] (domain) so it needs no domain body
     * and re-toggling never disturbs verification state.
     */
    @PatchMapping("/{id}/branding")
    fun setBranding(
        @PathVariable id: UUID,
        @Valid @RequestBody request: BrandingPreferenceRequest,
    ): ApiResponse<SiteDetailResponse> =
        ApiResponse.success(
            siteService.setBrandingPreference(CurrentUser.id(), id, requireNotNull(request.hideBranding)),
        )

    /**
     * Attempt to prove control of the site's domain (ADR-17). Deliberately a **200 with
     * `verified: false`** on a miss rather than a 4xx: not-installed-yet is the expected first answer,
     * and the dashboard renders it as persistent inline instructions instead of a dismissible error.
     * Rate-limited by its own tight tier — it makes an app-initiated outbound request (see
     * [eu.cookiekeeper.common.AuthenticatedRateLimitFilter]).
     */
    @PostMapping("/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
    ): ApiResponse<SiteVerificationResponse> = ApiResponse.success(siteVerificationService.verify(CurrentUser.id(), id))

    @DeleteMapping("/{id}")
    fun archive(
        @PathVariable id: UUID,
    ): ApiResponse<ArchiveResponse> {
        siteService.archive(CurrentUser.id(), id)
        return ApiResponse.success(ArchiveResponse())
    }

    /**
     * Reactivate an archived site (the inverse of [archive]). Idempotent for an already-active site.
     * Re-applies the site-cap guard and domain-availability check — a domain retaken by another active
     * site is a 409, being at the plan cap is a 403 — so restore can never bypass either.
     */
    @PostMapping("/{id}/restore")
    fun restore(
        @PathVariable id: UUID,
    ): ApiResponse<SiteDetailResponse> = ApiResponse.success(siteService.restore(CurrentUser.id(), id))
}
