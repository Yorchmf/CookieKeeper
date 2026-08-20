package eu.cookiekeeper.banner.dto

import eu.cookiekeeper.banner.BannerConfigDocument
import eu.cookiekeeper.banner.BannerConfigEntity
import eu.cookiekeeper.banner.BannerTextDefaults
import eu.cookiekeeper.banner.DEFAULT_CONSENT_LIFETIME_DAYS
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/**
 * Authenticated request to publish a new version of a site's banner configuration (the dashboard
 * customizer's Save). Every value is attacker-influenced and ends up served verbatim to visitors,
 * so the bean-validation here only guards payload shape/size; the semantic allow-listing (positions,
 * hex colors, the category taxonomy, supported languages, per-language text coverage) happens in
 * [eu.cookiekeeper.banner.BannerConfigValidator] before anything is persisted.
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
    /**
     * Days a consent choice stays valid. Optional: a client that doesn't send it keeps the default
     * 12 months rather than being rejected. The allow-list lives in the validator, not here.
     */
    val consentLifetimeDays: Int = DEFAULT_CONSENT_LIFETIME_DAYS,
) {
    companion object {
        const val MAX_SHORT = 16
        const val MAX_CATEGORIES = 8
        const val MAX_LANGUAGES = 5
    }
}

/**
 * Apply one site's published banner configuration to other sites the same account owns — the "I've styled
 * this once, don't make me re-enter it per domain" action. Only site *ids* travel: the document itself is
 * read server-side from the source's current published version, so this request can never smuggle in an
 * unvalidated banner document through a path that skips [eu.cookiekeeper.banner.BannerConfigValidator].
 *
 * [MAX_COPY_TARGETS] is a payload-shape bound, well above the 10-site Business cap (so the real ceiling is
 * the ownership check, not this) but low enough that the list can't be used to amplify one request into an
 * unbounded number of version writes.
 */
data class BannerConfigCopyRequest(
    @field:NotEmpty @field:Size(max = MAX_COPY_TARGETS) val targetSiteIds: List<UUID>,
)

/**
 * What the copy actually did: the sites that received a new version, and the source version that was
 * applied. Returned so the dashboard can report "applied to 3 sites" without a second round-trip.
 */
data class BannerConfigCopyResponse(
    val sourceVersion: Int,
    val copiedToSiteIds: List<UUID>,
)

/** Payload-shape ceiling on one copy request; see [BannerConfigCopyRequest]. */
const val MAX_COPY_TARGETS = 20

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

/**
 * One language's copy as submitted by the customizer.
 *
 * The preferences-panel fields added in ADR-19 Slice 2 are optional and default to blank: a blank
 * value is not an error, it means "use the shipped translation for this language"
 * (`BannerConfigValidator` substitutes it, so nothing is ever stored empty). The attribution text is
 * absent by design — it is server-owned, see `WidgetAttributionTexts`.
 */
data class BannerTextsRequest(
    val title: String,
    val description: String,
    val acceptAll: String,
    val rejectAll: String,
    val save: String,
    val preferences: String,
    val preferencesTitle: String = "",
    val close: String = "",
    val alwaysActive: String = "",
    @field:Size(max = BannerConfigUpdateRequest.MAX_CATEGORIES)
    val categoryLabels: Map<String, BannerCategoryTextRequest> = emptyMap(),
)

/** One category's copy in the preferences panel; blank fields fall back to the shipped translation. */
data class BannerCategoryTextRequest(
    val label: String = "",
    val description: String = "",
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
                // Backfill here rather than in the service so no read path can serve a config
                // predating ADR-19 Slice 2 with an empty preferences panel.
                config = BannerTextDefaults.complete(entity.config),
            )
    }
}
