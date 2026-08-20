package eu.cookiekeeper.banner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic behind both the banner's accessibility gate and its derived button label (ADR-28).
 * Reference ratios are the published WCAG examples, so a regression here is visible as a number and
 * not only as a theme that stopped being accepted.
 */
class ColorContrastTest {
    @Test
    fun `matches the WCAG reference ratios for the extremes`() {
        assertEquals(21.0, ColorContrast.ratio("#000000", "#ffffff")!!, 0.01)
        assertEquals(1.0, ColorContrast.ratio("#7f7f7f", "#7f7f7f")!!, 0.001)
    }

    @Test
    fun `is order-independent`() {
        val forward = ColorContrast.ratio("#2563eb", "#ffffff")
        val reverse = ColorContrast.ratio("#ffffff", "#2563eb")

        assertNotNull(forward)
        assertEquals(forward, reverse!!, 0.0001)
    }

    @Test
    fun `expands three-digit shorthand to the same color`() {
        assertEquals(
            ColorContrast.ratio("#ffffff", "#000000")!!,
            ColorContrast.ratio("#fff", "#000")!!,
            0.0001,
        )
    }

    @Test
    fun `reports null rather than a passing number for an unparseable color`() {
        // Callers treat null as "cannot judge" and reject; a silent 21.0 here would be a hole in the gate.
        assertNull(ColorContrast.ratio("rebeccapurple", "#ffffff"))
        assertNull(ColorContrast.ratio("#12345", "#ffffff"))
    }

    /**
     * The premise [WidgetConfigMapper.readableTextOn] rests on, and the reason the validator does not
     * check the button label: whatever the primary color, black or white clears 4.5:1 against it. The
     * two curves cross above the AA bar, so there is no color where both choices fail.
     */
    @Test
    fun `the derived button label clears AA against every possible primary color`() {
        val worst =
            (0..0xFFFFFF step 0x111)
                .map { value -> "#%06x".format(value) }
                .minOf { color -> ColorContrast.ratio(ColorContrast.readableTextOn(color), color)!! }

        assertTrue(worst >= ColorContrast.AA_NORMAL_TEXT, "worst derived label contrast was $worst")
    }

    @Test
    fun `picks white on dark and black on light`() {
        assertEquals("#ffffff", ColorContrast.readableTextOn("#0f172a"))
        assertEquals("#000000", ColorContrast.readableTextOn("#fde047"))
    }
}
