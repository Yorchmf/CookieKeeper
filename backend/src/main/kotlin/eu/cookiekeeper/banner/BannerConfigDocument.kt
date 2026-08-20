package eu.cookiekeeper.banner

/** Three months — the shortest lifetime offered, for customers who re-ask aggressively. */
const val QUARTERLY_CONSENT_LIFETIME_DAYS = 90

/** Six months — CNIL guidance, and the option a French customer's DPO is most likely to ask for. */
const val CNIL_CONSENT_LIFETIME_DAYS = 180

/** The lifetime a site gets unless it chooses otherwise, and the fallback for configs predating it. */
const val DEFAULT_CONSENT_LIFETIME_DAYS = 365

/**
 * How long a visitor's consent choice stays valid before the banner asks again.
 *
 * 12 months is the default and what every site had before this was configurable; the shorter
 * options exist because CNIL guidance is 6 months and a customer's own DPO may require it. The set
 * is closed rather than free-form: this value ends up as a cookie `Max-Age` in every visitor's
 * browser, and an arbitrary number there is a support burden with no upside. Only ADD to it —
 * removing a value would make an already-published config fail its own validator.
 */
val CONSENT_LIFETIME_DAY_OPTIONS: Set<Int> =
    setOf(
        QUARTERLY_CONSENT_LIFETIME_DAYS,
        CNIL_CONSENT_LIFETIME_DAYS,
        DEFAULT_CONSENT_LIFETIME_DAYS,
    )

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
    /**
     * Days a consent choice stays valid, one of [CONSENT_LIFETIME_DAY_OPTIONS]. Defaulted so a
     * document stored before this field existed still deserializes with the 12 months it was
     * written under. The widget stamps it into the consent cookie at the moment of choice, so
     * lowering it re-prompts visitors as they next decide, not retroactively.
     */
    val consentLifetimeDays: Int = DEFAULT_CONSENT_LIFETIME_DAYS,
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

/**
 * One language's banner copy.
 *
 * The four fields added in ADR-19 Slice 2 ([preferencesTitle], [close], [alwaysActive],
 * [categoryLabels]) default to blank/empty so a document written before they existed still
 * deserializes; [BannerTextDefaults] fills any blank from the shipped translation for that
 * language on every read, so neither the editor nor the widget ever sees a gap.
 *
 * The widget also renders a "Powered by CookieKeeper" attribution, which is deliberately NOT a field
 * here: it is server-owned ([WidgetAttributionTexts]) because suppressing it is a paid entitlement,
 * and a customer-editable string would be a free way around it.
 */
data class BannerTexts(
    val title: String,
    val description: String,
    val acceptAll: String,
    val rejectAll: String,
    val save: String,
    val preferences: String,
    /** Heading of the preferences panel. */
    val preferencesTitle: String = "",
    /** Label of the panel's close control. */
    val close: String = "",
    /** Badge shown beside categories the visitor cannot switch off. */
    val alwaysActive: String = "",
    /** Category key → the label and explanation shown for it in the preferences panel. */
    val categoryLabels: Map<String, BannerCategoryText> = emptyMap(),
)

data class BannerCategoryText(
    val label: String,
    val description: String,
)
