package com.complyr.banner

import com.complyr.banner.dto.BannerCategoryRequest
import com.complyr.banner.dto.BannerConfigUpdateRequest
import com.complyr.banner.dto.BannerTextsRequest
import com.complyr.common.SupportedLocales

/**
 * Turns an untrusted [BannerConfigUpdateRequest] into a validated [BannerConfigDocument], or throws
 * [InvalidBannerConfigException] (400). This is the trust boundary for the banner: the resulting
 * document is stored in `config_jsonb` and served verbatim to every visitor's browser, so each field
 * is allow-listed, not merely length-checked:
 *  - `position` ∈ a fixed set; colors must be strict `#RGB`/`#RRGGBB` hex (they flow into inline
 *    styles, so an unconstrained value would be a CSS-injection vector);
 *  - category keys must be in the canonical taxonomy, `necessary` must be present, and `required`/
 *    `enabledByDefault` are DERIVED from the taxonomy — the client can never pre-enable a tracker (GDPR);
 *  - languages must be supported, and `texts` must fully cover the offered set.
 *
 * Error messages are static and never echo the offending value (no reflected-input surface).
 */
object BannerConfigValidator {
    private val POSITIONS: Set<String> = setOf("bottom", "top", "center")
    private val HEX_COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    private const val MAX_TITLE = 120
    private const val MAX_DESCRIPTION = 600
    private const val MAX_BUTTON = 60

    fun validate(request: BannerConfigUpdateRequest): BannerConfigDocument {
        val position = request.position.trim().lowercase()
        if (position !in POSITIONS) invalid("position must be one of: bottom, top, center")

        val theme =
            BannerTheme(
                primaryColor = color(request.theme.primaryColor, "primaryColor"),
                background = color(request.theme.background, "background"),
                textColor = color(request.theme.textColor, "textColor"),
            )

        val languages = normalizeLanguages(request.languages)
        val defaultLanguage =
            SupportedLocales.normalizeOrNull(request.defaultLanguage)
                ?: invalid("defaultLanguage is not a supported language")
        if (defaultLanguage !in languages) invalid("defaultLanguage must be one of the offered languages")

        val categories = normalizeCategories(request.categories)
        val texts = normalizeTexts(request.texts, languages)

        return BannerConfigDocument(
            position = position,
            theme = theme,
            categories = categories,
            languages = languages,
            defaultLanguage = defaultLanguage,
            texts = texts,
        )
    }

    private fun normalizeLanguages(raw: List<String>): List<String> {
        val languages =
            raw
                .map { SupportedLocales.normalizeOrNull(it) ?: invalid("one or more languages are not supported") }
                .distinct()
        if (languages.isEmpty()) invalid("at least one language is required")
        return languages
    }

    private fun normalizeCategories(raw: List<BannerCategoryRequest>): List<BannerCategory> {
        val seen = mutableSetOf<String>()
        val categories =
            raw.map { requested ->
                val key = requested.key.trim().lowercase()
                val taxonomy =
                    ConsentCategory.entries.firstOrNull { it.key == key }
                        ?: invalid("unknown category")
                if (!seen.add(key)) invalid("duplicate category")
                // Derive required/enabledByDefault from the taxonomy: only `necessary` may be on by default.
                BannerCategory(key = taxonomy.key, required = taxonomy.required, enabledByDefault = taxonomy.required)
            }
        if (ConsentCategory.NECESSARY.key !in seen) invalid("the necessary category is required")
        return categories
    }

    private fun normalizeTexts(
        raw: Map<String, BannerTextsRequest>,
        languages: List<String>,
    ): Map<String, BannerTexts> {
        // Normalize incoming keys and drop entries for languages that aren't offered.
        val provided =
            raw.entries
                .mapNotNull { (key, value) ->
                    SupportedLocales.normalizeOrNull(key)?.let { it to value }
                }.toMap()
        return languages.associateWith { language ->
            val texts = provided[language] ?: invalid("texts are missing for an offered language")
            BannerTexts(
                title = text(texts.title, "title", MAX_TITLE),
                description = text(texts.description, "description", MAX_DESCRIPTION),
                acceptAll = text(texts.acceptAll, "acceptAll", MAX_BUTTON),
                rejectAll = text(texts.rejectAll, "rejectAll", MAX_BUTTON),
                save = text(texts.save, "save", MAX_BUTTON),
                preferences = text(texts.preferences, "preferences", MAX_BUTTON),
            )
        }
    }

    private fun color(
        value: String,
        field: String,
    ): String {
        val trimmed = value.trim()
        if (!HEX_COLOR.matches(trimmed)) invalid("$field must be a hex color (e.g. #2563eb)")
        return trimmed
    }

    private fun text(
        value: String,
        field: String,
        maxLength: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) invalid("$field must not be blank")
        if (trimmed.length > maxLength) invalid("$field is too long")
        return trimmed
    }

    private fun invalid(message: String): Nothing = throw InvalidBannerConfigException(message)
}
