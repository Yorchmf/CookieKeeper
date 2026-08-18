package eu.cookiekeeper.banner

/**
 * Fills the gaps ADR-19 Slice 2 left in already-stored configs.
 *
 * The preferences-panel copy (`preferencesTitle`, `close`, `alwaysActive`, `categoryLabels`) did not
 * exist when the sites live today were seeded, so their `config_jsonb` has no such keys and
 * [BannerTexts] deserializes them to blank. Rather than rewrite every row with a Flyway data
 * migration — configs are append-only versions, not mutable state — every *read* runs through here
 * and substitutes the shipped translation for that language ([DefaultBannerTexts]).
 *
 * The effect: a German visitor gets German panel labels immediately, the editor opens pre-filled
 * instead of blank, and the customer's own wording — once saved — always wins because only blank
 * values are replaced. Nothing is force-migrated; the backfill becomes persistent on the next save.
 */
object BannerTextDefaults {
    /** Backfills every language bundle in [document], limited to the categories it actually offers. */
    fun complete(document: BannerConfigDocument): BannerConfigDocument =
        document.copy(
            texts =
                document.texts.mapValues { (language, texts) ->
                    complete(texts, language, document.categoryKeys())
                },
        )

    /**
     * Backfills one language bundle. Only the ADR-19 Slice 2 fields are considered: the original six
     * are guaranteed non-blank by [BannerConfigValidator], so silently repairing them here would hide
     * a validation regression rather than fix it.
     */
    fun complete(
        texts: BannerTexts,
        language: String,
        categoryKeys: Set<String>,
    ): BannerTexts {
        val shipped = DefaultBannerTexts.BY_LANGUAGE[language] ?: DefaultBannerTexts.ENGLISH
        return texts.copy(
            preferencesTitle = texts.preferencesTitle.ifBlank { shipped.preferencesTitle },
            close = texts.close.ifBlank { shipped.close },
            alwaysActive = texts.alwaysActive.ifBlank { shipped.alwaysActive },
            categoryLabels = completeCategoryLabels(texts.categoryLabels, shipped, categoryKeys),
        )
    }

    private fun completeCategoryLabels(
        stored: Map<String, BannerCategoryText>,
        shipped: BannerTexts,
        categoryKeys: Set<String>,
    ): Map<String, BannerCategoryText> =
        categoryKeys
            .mapNotNull { key ->
                val fallback = shipped.categoryLabels[key]
                val current = stored[key]
                val label = current?.label?.ifBlank { fallback?.label } ?: fallback?.label
                val description = current?.description?.ifBlank { fallback?.description } ?: fallback?.description
                // A category outside the shipped taxonomy with no stored copy has nothing to render;
                // dropping it lets the widget fall back rather than show an empty row.
                if (label.isNullOrBlank()) null else key to BannerCategoryText(label, description.orEmpty())
            }.toMap()
}
