package com.complyr.banner

/**
 * The versioned per-site widget configuration serialized into `banner_configs.config_jsonb`
 * and served verbatim to the widget via `GET /api/v1/widget-config/{siteKey}`.
 *
 * This is a value document, not an entity: a new [BannerConfigEntity] version is appended
 * whenever the customer edits their banner (configs are never overwritten in place).
 * All user-facing text lives in [texts] keyed by language (constraint #6: no hardcoded
 * strings — the widget's i18n is this per-site config).
 */
data class BannerConfigDocument(
    /** Where the banner renders: `bottom` | `top` | `center`. */
    val position: String,
    val theme: BannerTheme,
    /** Categories offered on the banner, in display order; `necessary` is always present. */
    val categories: List<BannerCategory>,
    /** Language codes the banner offers, e.g. `["en","de"]`. */
    val languages: List<String>,
    /** Language used when the visitor's browser language is not in [languages]. */
    val defaultLanguage: String,
    /** Per-language text bundle, keyed by language code. Must cover every entry in [languages]. */
    val texts: Map<String, BannerTexts>,
) {
    /** The set of category keys this config declares — the allow-list for consent validation. */
    fun categoryKeys(): Set<String> = categories.map { it.key }.toSet()
}

data class BannerTheme(
    val primaryColor: String,
    val background: String,
    val textColor: String,
)

data class BannerCategory(
    val key: String,
    /** Required categories (necessary) are shown locked-on and cannot be rejected. */
    val required: Boolean,
    /** Initial toggle state for optional categories before the visitor chooses. */
    val enabledByDefault: Boolean,
)

data class BannerTexts(
    val title: String,
    val description: String,
    val acceptAll: String,
    val rejectAll: String,
    val save: String,
    val preferences: String,
)
