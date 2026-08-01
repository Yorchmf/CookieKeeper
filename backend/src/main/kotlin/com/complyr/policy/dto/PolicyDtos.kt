package com.complyr.policy.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Authenticated request to (re)generate and publish a site's cookie policy. The business details fill
 * the template ([com.complyr.policy.PolicyRenderer]); [languages] optionally narrows which of the five
 * supported languages to render (defaults to the site's banner languages). All values are validated at
 * the boundary here and HTML-escaped at render time — never trusted downstream.
 */
data class PolicyGenerationRequest(
    @field:NotBlank
    @field:Size(max = MAX_COMPANY_NAME)
    val companyName: String,
    @field:NotBlank
    @field:Email
    @field:Size(max = MAX_EMAIL)
    val contactEmail: String,
    // Optional: when blank the service defaults it to https://{site.domain}. Bounded to a sane URL length.
    @field:Size(max = MAX_URL)
    val websiteUrl: String? = null,
    @field:Size(max = MAX_ADDRESS)
    val address: String? = null,
    // Optional subset of supported languages; unsupported entries are dropped, empty/absent → defaults.
    // Bounded at the boundary so a request can't submit an oversized array to churn the normalize pass.
    @field:Size(max = MAX_LANGUAGES)
    val languages: List<String>? = null,
) {
    companion object {
        const val MAX_COMPANY_NAME = 200
        const val MAX_EMAIL = 254
        const val MAX_URL = 2048
        const val MAX_ADDRESS = 500
        const val MAX_LANGUAGES = 5
    }
}

/** Result of a generate call: the new version and where it is hosted. */
data class PolicyGenerationResponse(
    val version: Int,
    val publicId: String,
    val hostedUrl: String,
    val languages: List<String>,
)

/** The site's currently published policy, for the dashboard's policy view. */
data class PolicyCurrentResponse(
    val version: Int,
    val publicId: String,
    val hostedUrl: String,
    val languages: List<String>,
    val publishedAt: Instant?,
)

/**
 * Public hosted-page payload (no auth): the rendered [html] block for one [language], plus the
 * [availableLanguages] of this version for the page's language switcher and [companyName]/[version]
 * for the page title and footer. Addressed only by the opaque public id.
 */
data class PublicPolicyResponse(
    val version: Int,
    val language: String,
    val availableLanguages: List<String>,
    val companyName: String,
    val html: String,
    val publishedAt: Instant?,
)
