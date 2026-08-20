package eu.cookiekeeper.banner

import kotlin.math.pow

/**
 * WCAG 2.2 relative luminance and contrast ratio for the hex colors a customer picks for their banner
 * (ADR-28).
 *
 * Two callers, deliberately sharing one implementation: [BannerConfigValidator] *rejects* a theme whose
 * text would be illegible, and [WidgetConfigMapper] *derives* the button label color. Two copies of this
 * arithmetic would be two definitions of "readable", and the one in the validator is the one a regulator
 * would test against.
 *
 * Colors are `#RGB`/`#RRGGBB` (the only shape [BannerConfigValidator] admits); anything else yields
 * `null` and every caller treats that as "cannot judge" rather than as a pass.
 */
object ColorContrast {
    /** WCAG 1.4.3 AA for body text — the banner message, category labels and descriptions. */
    const val AA_NORMAL_TEXT = 4.5

    /** WCAG 1.4.11 AA for UI component boundaries and states — the button fill, the badge outline. */
    const val AA_NON_TEXT = 3.0

    /**
     * Contrast ratio between two colors, `1.0`–`21.0`, or null if either is not parseable hex.
     * `(lighter + 0.05) / (darker + 0.05)`, per the WCAG definition — order-independent.
     */
    fun ratio(
        first: String,
        second: String,
    ): Double? {
        val a = relativeLuminance(first) ?: return null
        val b = relativeLuminance(second) ?: return null
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET)
    }

    /**
     * Black or white — whichever contrasts better with [background]. Used for the button label, whose
     * backdrop is the customer's chosen primary color, so that pair can never be the failing one.
     * An unparseable color yields white, matching the widget's own default button label.
     */
    fun readableTextOn(background: String): String {
        val luminance = relativeLuminance(background) ?: return WHITE
        val contrastWithWhite = (WHITE_LUMINANCE + CONTRAST_OFFSET) / (luminance + CONTRAST_OFFSET)
        val contrastWithBlack = (luminance + CONTRAST_OFFSET) / CONTRAST_OFFSET
        return if (contrastWithWhite >= contrastWithBlack) WHITE else BLACK
    }

    /** WCAG relative luminance of a `#RGB`/`#RRGGBB` color, or null if it is neither. */
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
