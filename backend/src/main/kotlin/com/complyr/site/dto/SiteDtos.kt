package com.complyr.site.dto

import com.complyr.site.SiteEntity
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateSiteRequest(
    @field:NotBlank
    val domain: String,
)

data class UpdateSiteRequest(
    val domain: String? = null,
)

data class SiteResponse(
    val id: UUID,
    val domain: String,
    val siteKey: String,
    val status: String,
    val verifiedAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(site: SiteEntity): SiteResponse =
            SiteResponse(
                id = site.id,
                domain = site.domain,
                siteKey = site.siteKey,
                status = site.status.dbValue,
                verifiedAt = site.verifiedAt,
                createdAt = site.createdAt,
            )
    }
}

data class SiteDetailResponse(
    val id: UUID,
    val domain: String,
    val siteKey: String,
    val status: String,
    val verifiedAt: Instant?,
    val createdAt: Instant,
    val embedSnippet: String,
) {
    companion object {
        fun from(
            site: SiteEntity,
            embedSnippet: String,
        ): SiteDetailResponse =
            SiteDetailResponse(
                id = site.id,
                domain = site.domain,
                siteKey = site.siteKey,
                status = site.status.dbValue,
                verifiedAt = site.verifiedAt,
                createdAt = site.createdAt,
                embedSnippet = embedSnippet,
            )
    }
}

data class ArchiveResponse(
    val archived: Boolean = true,
)
