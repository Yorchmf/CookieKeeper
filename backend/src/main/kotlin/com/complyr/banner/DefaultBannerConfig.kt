package com.complyr.banner

/**
 * The out-of-the-box banner configuration seeded for every new site so the widget works the
 * moment the snippet is embedded — before the customer customizes anything. Ships all five
 * supported languages (constraint #6: i18n from day one, copy in [DefaultBannerTexts]) and the
 * full category taxonomy with GDPR-safe defaults (only `necessary` on before an explicit choice).
 */
object DefaultBannerConfig {
    const val FIRST_VERSION: Int = 1

    fun document(): BannerConfigDocument =
        BannerConfigDocument(
            position = "bottom",
            theme =
                BannerTheme(
                    primaryColor = "#2563eb",
                    background = "#ffffff",
                    textColor = "#0f172a",
                ),
            categories =
                ConsentCategory.entries.map { category ->
                    BannerCategory(
                        key = category.key,
                        required = category.required,
                        // GDPR: nothing but strictly-necessary may be pre-enabled.
                        enabledByDefault = category.required,
                    )
                },
            languages = DefaultBannerTexts.BY_LANGUAGE.keys.toList(),
            defaultLanguage = "en",
            texts = DefaultBannerTexts.BY_LANGUAGE,
        )
}
