package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.WidgetCategory
import eu.cookiekeeper.banner.dto.WidgetCategoryText
import eu.cookiekeeper.banner.dto.WidgetColors
import eu.cookiekeeper.banner.dto.WidgetConfigPayload
import eu.cookiekeeper.banner.dto.WidgetConfigResponse
import eu.cookiekeeper.banner.dto.WidgetTexts

/**
 * Translates the stored [BannerConfigDocument] into the widget's own contract
 * ([WidgetConfigPayload]) — the schema half of ADR-19. The two shapes were designed
 * independently and diverge in four places; each divergence is resolved here, once, rather
 * than by loosening either side:
 *
 *  1. **Colors 3 → 4.** The stored theme has `primaryColor`/`background`/`textColor`; the widget
 *     needs a `buttonText` too. It is DERIVED ([readableTextOn]) rather than stored, so a customer
 *     can never pick a button/label pair that fails contrast, and no schema or UI change is needed.
 *  2. **Position.** The editor offers `center`; the widget renders only `bottom`/`top`. Anything
 *     that is not `top` maps to `bottom`. The customizer no longer offers `center`, so this only
 *     covers configs published before that change.
 *  3. **Categories.** Stored `key` → widget `id`; `enabledByDefault` is dropped (the widget derives
 *     the pre-choice state from `required`, which is the GDPR-correct rule anyway).
 *  4. **Texts.** Stored `description` → widget `message`. The preferences-panel copy added in
 *     Slice 2 passes through under the widget's own names; `poweredBy`/`opensInNewTab` are not in
 *     the stored document at all and are injected here from [WidgetAttributionTexts], which is what
 *     keeps the paid `removeBranding` entitlement from being editable away.
 */
object WidgetConfigMapper {
    fun toPayload(response: WidgetConfigResponse): WidgetConfigPayload {
        val config = response.config
        return WidgetConfigPayload(
            version = response.bannerVersion,
            colors =
                WidgetColors(
                    background = config.theme.background,
                    text = config.theme.textColor,
                    button = config.theme.primaryColor,
                    buttonText = readableTextOn(config.theme.primaryColor),
                ),
            position = if (config.position == POSITION_TOP) POSITION_TOP else POSITION_BOTTOM,
            texts = config.texts.mapValues { (language, texts) -> toWidgetTexts(texts, language) },
            defaultLanguage = config.defaultLanguage,
            categories = config.categories.map { WidgetCategory(id = it.key, required = it.required) },
            removeBranding = response.removeBranding,
            consentLifetimeDays = config.consentLifetimeDays,
            consentBasisVersion = response.consentBasisVersion,
        )
    }

    private fun toWidgetTexts(
        texts: BannerTexts,
        language: String,
    ): WidgetTexts {
        val attribution = WidgetAttributionTexts.forLanguage(language)
        return WidgetTexts(
            title = texts.title,
            message = texts.description,
            acceptAll = texts.acceptAll,
            rejectAll = texts.rejectAll,
            preferences = texts.preferences,
            save = texts.save,
            preferencesTitle = texts.preferencesTitle,
            close = texts.close,
            alwaysActive = texts.alwaysActive,
            poweredBy = attribution.poweredBy,
            opensInNewTab = attribution.opensInNewTab,
            categoryLabels =
                texts.categoryLabels.mapValues { (_, category) ->
                    WidgetCategoryText(label = category.label, description = category.description)
                },
        )
    }

    /**
     * Black or white — whichever has the higher WCAG contrast ratio against [background].
     * Used for the button label, whose background is the customer's chosen primary color.
     * Delegates to [ColorContrast], which is also what [BannerConfigValidator] judges themes by.
     */
    fun readableTextOn(background: String): String = ColorContrast.readableTextOn(background)

    private const val POSITION_TOP = "top"
    private const val POSITION_BOTTOM = "bottom"
}
