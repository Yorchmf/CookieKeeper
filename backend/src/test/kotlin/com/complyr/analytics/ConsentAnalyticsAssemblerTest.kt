package com.complyr.analytics

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Unit tests for [ConsentAnalyticsAssembler] — the shared consent arithmetic behind both the per-site and
 * the cross-site reads. Locks the accept/reject/custom breakdown, the dense day-ordered trend, the opt-in
 * rate (including the division-by-zero guard), and the banner-impression interaction rate (Track 4 Slice D),
 * so the two views can never disagree on a figure.
 */
class ConsentAnalyticsAssemblerTest {
    private val assembler = ConsentAnalyticsAssembler()

    private val aug12 = LocalDate.parse("2026-08-12")
    private val aug13 = LocalDate.parse("2026-08-13")

    @Test
    fun `breakdown sums per action and totalEvents is their sum`() {
        val consent =
            assembler.assemble(
                daily =
                    listOf(
                        DailyActionCount(aug12, "accept_all", 30),
                        DailyActionCount(aug12, "reject_all", 50),
                        DailyActionCount(aug13, "accept_all", 10),
                        DailyActionCount(aug13, "custom", 20),
                    ),
                optIn = emptyList(),
                languages = emptyList(),
                impressions = 0,
            )

        assertEquals(40, consent.byAction.acceptAll)
        assertEquals(50, consent.byAction.rejectAll)
        assertEquals(20, consent.byAction.custom)
        assertEquals(110, consent.totalEvents)
    }

    @Test
    fun `trend is one dense point per day, ascending, with per-day totals`() {
        // Rows deliberately out of order and split per action — the assembler must group and sort them.
        val consent =
            assembler.assemble(
                daily =
                    listOf(
                        DailyActionCount(aug13, "accept_all", 4),
                        DailyActionCount(aug12, "accept_all", 3),
                        DailyActionCount(aug12, "reject_all", 2),
                        DailyActionCount(aug13, "custom", 1),
                    ),
                optIn = emptyList(),
                languages = emptyList(),
                impressions = 0,
            )

        assertEquals(listOf(aug12, aug13), consent.trend.map { it.date })
        val first = consent.trend.first()
        assertEquals(3, first.acceptAll)
        assertEquals(2, first.rejectAll)
        assertEquals(0, first.custom)
        assertEquals(5, first.total)
        val second = consent.trend.last()
        assertEquals(5, second.total)
    }

    @Test
    fun `opt-in rate is the true fraction, and zero decisions yield rate 0 not a divide-by-zero`() {
        val consent =
            assembler.assemble(
                daily = emptyList(),
                optIn =
                    listOf(
                        CategoryOptInCount(category = "statistics", optIns = 3, decisions = 4),
                        CategoryOptInCount(category = "marketing", optIns = 0, decisions = 0),
                    ),
                languages = emptyList(),
                impressions = 0,
            )

        val stats = consent.categoryOptIn.single { it.category == "statistics" }
        assertEquals(0.75, stats.rate)
        val marketing = consent.categoryOptIn.single { it.category == "marketing" }
        assertEquals(0.0, marketing.rate)
    }

    @Test
    fun `language split maps through preserving order and counts`() {
        val consent =
            assembler.assemble(
                daily = emptyList(),
                optIn = emptyList(),
                languages = listOf(LanguageCountRow("de", 12), LanguageCountRow("en", 7)),
                impressions = 0,
            )

        assertEquals(listOf("de" to 12L, "en" to 7L), consent.languageSplit.map { it.lang to it.count })
    }

    @Test
    fun `impressions pass through and interaction rate is events over impressions`() {
        // 110 decisions against 200 impressions → 0.55 interaction rate.
        val consent =
            assembler.assemble(
                daily =
                    listOf(
                        DailyActionCount(aug12, "accept_all", 60),
                        DailyActionCount(aug12, "reject_all", 30),
                        DailyActionCount(aug13, "custom", 20),
                    ),
                optIn = emptyList(),
                languages = emptyList(),
                impressions = 200,
            )

        assertEquals(200, consent.impressions)
        assertEquals(110, consent.totalEvents)
        assertEquals(0.55, consent.interactionRate)
    }

    @Test
    fun `zero impressions yield interaction rate 0 not a divide-by-zero`() {
        val consent =
            assembler.assemble(
                daily = listOf(DailyActionCount(aug12, "accept_all", 5)),
                optIn = emptyList(),
                languages = emptyList(),
                impressions = 0,
            )

        assertEquals(0, consent.impressions)
        assertEquals(5, consent.totalEvents)
        // Decisions with no recorded impression must not blow up — rate is defined as 0, not NaN/Infinity.
        assertEquals(0.0, consent.interactionRate)
    }

    @Test
    fun `interaction rate can exceed 1 when decisions outnumber impressions`() {
        // Re-consent on a page that didn't re-show the banner, or divergent retention windows: decisions
        // can outnumber impressions. The assembler reports the raw ratio rather than clamping it.
        val consent =
            assembler.assemble(
                daily = listOf(DailyActionCount(aug12, "accept_all", 3)),
                optIn = emptyList(),
                languages = emptyList(),
                impressions = 2,
            )

        assertEquals(1.5, consent.interactionRate)
    }

    @Test
    fun `summarize returns the action totals, their sum, and the impressions, matching assemble's breakdown`() {
        val daily =
            listOf(
                DailyActionCount(aug12, "accept_all", 30),
                DailyActionCount(aug12, "reject_all", 50),
                DailyActionCount(aug13, "accept_all", 10),
                DailyActionCount(aug13, "custom", 20),
            )

        val summary = assembler.summarize(daily, impressions = 300)

        assertEquals(40, summary.byAction.acceptAll)
        assertEquals(50, summary.byAction.rejectAll)
        assertEquals(20, summary.byAction.custom)
        assertEquals(110, summary.totalEvents)
        assertEquals(300, summary.impressions)
        // The lean baseline and the full view count an action mix the same way — one arithmetic, one source.
        assertEquals(assembler.assemble(daily, emptyList(), emptyList(), impressions = 300).byAction, summary.byAction)
    }
}
