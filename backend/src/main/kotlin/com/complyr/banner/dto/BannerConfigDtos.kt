package com.complyr.banner.dto

import com.complyr.banner.BannerConfigDocument
import com.complyr.banner.BannerConfigEntity
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Authenticated request to publish a new version of a site's banner configuration (the dashboard
 * customizer's Save). Every value is attacker-influenced and ends up served verbatim to visitors,
 * so the bean-validation here only guards payload shape/size; the semantic allow-listing (positions,
 * hex colors, the category taxonomy, supported languages, per-language text coverage) happens in
 * [com.complyr.banner.BannerConfigValidator] before anything is persisted.
 */
data class BannerConfigUpdateRequest(
    @field:Size(max = MAX_SHORT) val position: String,
    val theme: BannerThemeRequest,
    @field:NotEmpty @field:Size(max = MAX_CATEGORIES) val categories: List<BannerCategoryRequest>,
    @field:NotEmpty @field:Size(max = MAX_LANGUAGES) val languages: List<
        @Size(max = MAX_SHORT)
        String,
    >,
    @field:Size(max = MAX_SHORT) val defaultLanguage: String,
    @field:NotEmpty @field:Size(max = MAX_LANGUAGES) val texts: Map<String, BannerTextsRequest>,
) {
    companion object {
        const val MAX_SHORT = 16
        const val MAX_CATEGORIES = 8
        const val MAX_LANGUAGES = 5
    }
}

data class BannerThemeRequest(
    val primaryColor: String,
    val background: String,
    val textColor: String,
)

/**
 * A category the customer chooses to offer, in display order. Only [key] and presence are honored:
 * `required` and `enabledByDefault` are derived from the canonical taxonomy so the client can never
 * pre-enable a non-necessary category (GDPR — nothing but strictly-necessary on before a choice).
 */
data class BannerCategoryRequest(
    @field:Size(max = BannerConfigUpdateRequest.MAX_SHORT) val key: String,
)

data class BannerTextsRequest(
    val title: String,
    val description: String,
    val acceptAll: String,
    val rejectAll: String,
    val save: String,
    val preferences: String,
)

/**
 * The current (or freshly published) banner configuration for the dashboard customizer: the version,
 * when it was published, and the full document to render the editor and preview from.
 */
data class BannerConfigResponse(
    val version: Int,
    val publishedAt: Instant?,
    val config: BannerConfigDocument,
) {
    companion object {
        fun from(entity: BannerConfigEntity): BannerConfigResponse =
            BannerConfigResponse(
                version = entity.version,
                publishedAt = entity.publishedAt,
                config = entity.config,
            )
    }
}
