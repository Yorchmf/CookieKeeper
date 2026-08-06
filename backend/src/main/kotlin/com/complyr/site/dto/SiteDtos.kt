package com.complyr.site.dto

import com.complyr.site.DnsTxtLookup
import com.complyr.site.SiteEntity
import com.complyr.site.VerificationMethod
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

/**
 * The site page's payload. It carries both verification *instructions* ([dnsRecordName] /
 * [dnsRecordValue], alongside the existing [embedSnippet]) and verification *state*, so the dashboard
 * renders the whole verify card from one response and can never show a record name that disagrees with
 * what [com.complyr.site.DnsTxtLookup] actually queries.
 */
data class SiteDetailResponse(
    val id: UUID,
    val domain: String,
    val siteKey: String,
    val status: String,
    val verifiedAt: Instant?,
    val verificationMethod: String?,
    val createdAt: Instant,
    val embedSnippet: String,
    val dnsRecordName: String,
    val dnsRecordValue: String,
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
                verificationMethod = site.verificationMethod?.dbValue,
                createdAt = site.createdAt,
                embedSnippet = embedSnippet,
                dnsRecordName = "${DnsTxtLookup.RECORD_PREFIX}.${site.domain}",
                dnsRecordValue = site.siteKey,
            )
    }
}

/**
 * The outcome of one verification attempt. A miss is a 200 with `verified: false` and a [reason], not
 * an error — see [com.complyr.site.SiteVerificationService] for why that distinction is load-bearing
 * for both the UI and the no-oracle contract.
 */
data class SiteVerificationResponse(
    val verified: Boolean,
    val verifiedAt: Instant? = null,
    val method: String? = null,
    val reason: String? = null,
) {
    companion object {
        fun verified(
            at: Instant,
            method: VerificationMethod?,
        ): SiteVerificationResponse = SiteVerificationResponse(verified = true, verifiedAt = at, method = method?.dbValue)

        fun failed(reason: String): SiteVerificationResponse = SiteVerificationResponse(verified = false, reason = reason)
    }
}

data class ArchiveResponse(
    val archived: Boolean = true,
)
