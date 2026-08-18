package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.WidgetCategory
import eu.cookiekeeper.banner.dto.WidgetCategoryText
import eu.cookiekeeper.banner.dto.WidgetColors
import eu.cookiekeeper.banner.dto.WidgetConfigPayload
import eu.cookiekeeper.banner.dto.WidgetConfigResponse
import eu.cookiekeeper.banner.dto.WidgetTexts
import kotlin.math.pow

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
     * An unparseable color (only possible for a row written before the hex validator) yields
     * white, matching the widget's own default button label.
     */
    fun readableTextOn(background: String): String {
        val luminance = relativeLuminance(background) ?: return WHITE
        val contrastWithWhite = (WHITE_LUMINANCE + CONTRAST_OFFSET) / (luminance + CONTRAST_OFFSET)
        val contrastWithBlack = (luminance + CONTRAST_OFFSET) / CONTRAST_OFFSET
        return if (contrastWithWhite >= contrastWithBlack) WHITE else BLACK
    }

    /** WCAG 2.2 relative luminance of a `#RGB`/`#RRGGBB` color, or null if it is neither. */
    private fun relativeLuminance(color: String): Double? {
        val channels = channels(color) ?: return null
        val (red, green, blue) = channels
        return RED_WEIGHT * linearize(red) + GREEN_WEIGHT * linearize(green) + BLUE_WEIGHT * linearize(blue)
    }

    private fun channels(color: String): Triple<Int, Int, Int>? {
        val value = sixDigitHex(color)?.toIntOrNull(HEX_RADIX) ?: return null
        return Triple(
            (value shr RED_SHIFT) and BYTE_MASK,
            (value shr GREEN_SHIFT) and BYTE_MASK,
            value and BYTE_MASK,
        )
    }

    /** Strips `#` and expands `#RGB` shorthand; null when the value is not hex of either length. */
    private fun sixDigitHex(color: String): String? {
        val trimmed = color.trim().removePrefix("#")
        return when (trimmed.length) {
            SHORT_HEX_LENGTH -> trimmed.map { "$it$it" }.joinToString("")
            FULL_HEX_LENGTH -> trimmed
            else -> null
        }
    }

    /** sRGB channel (0–255) to its linear-light value, per the WCAG definition. */
    private fun linearize(channel: Int): Double {
        val normalized = channel / MAX_CHANNEL
        return if (normalized <= SRGB_KNEE) {
            normalized / SRGB_LOW_SLOPE
        } else {
            ((normalized + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_EXPONENT)
        }
    }

    private const val POSITION_TOP = "top"
    private const val POSITION_BOTTOM = "bottom"

    private const val WHITE = "#ffffff"
    private const val BLACK = "#000000"

    private const val SHORT_HEX_LENGTH = 3
    private const val FULL_HEX_LENGTH = 6
    private const val HEX_RADIX = 16
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF

    private const val MAX_CHANNEL = 255.0
    private const val SRGB_KNEE = 0.03928
    private const val SRGB_LOW_SLOPE = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_SCALE = 1.055
    private const val SRGB_EXPONENT = 2.4
    private const val RED_WEIGHT = 0.2126
    private const val GREEN_WEIGHT = 0.7152
    private const val BLUE_WEIGHT = 0.0722

    /** Luminance of white, and the 0.05 flare term both sides of the WCAG contrast ratio carry. */
    private const val WHITE_LUMINANCE = 1.0
    private const val CONTRAST_OFFSET = 0.05
}
