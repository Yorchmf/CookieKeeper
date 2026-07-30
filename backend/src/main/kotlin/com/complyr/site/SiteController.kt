package com.complyr.site

import com.complyr.common.ApiMeta
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import com.complyr.common.InvalidQueryParamException
import com.complyr.site.dto.ArchiveResponse
import com.complyr.site.dto.CreateSiteRequest
import com.complyr.site.dto.SiteDetailResponse
import com.complyr.site.dto.SiteResponse
import com.complyr.site.dto.UpdateSiteRequest
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

    @DeleteMapping("/{id}")
    fun archive(
        @PathVariable id: UUID,
    ): ApiResponse<ArchiveResponse> {
        siteService.archive(CurrentUser.id(), id)
        return ApiResponse.success(ArchiveResponse())
    }
}
