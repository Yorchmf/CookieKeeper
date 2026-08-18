package eu.cookiekeeper.billing

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Guards the invariant [eu.cookiekeeper.scan.ScheduledRescanJob]'s SQL pre-filter depends on: every plan
 * cadence is at least 7 days. The job uses the shortest cadence (7 days, further widened by a 1-day DST
 * safety margin) as a coarse candidate filter, then applies the exact per-plan cadence in Kotlin. If a
 * shorter tier (e.g. a DAILY option) were ever added without revisiting that cutoff, sites on it would be
 * silently skipped for close to a week. This test fails the build the moment that assumption breaks.
 */
class RescanFrequencyTest {
    @Test
    fun `every cadence is at least the 7-day SQL pre-filter window`() {
        RescanFrequency.entries.forEach { frequency ->
            // Conservative lower bound on the period's real length: the shortest month is 28 days, and
            // weeks are stored as days. Both current cadences resolve to >= 7 this way (weekly = 7).
            val minimumDays = frequency.interval.toTotalMonths() * SHORTEST_MONTH_DAYS + frequency.interval.days
            assertTrue(
                minimumDays >= SQL_PREFILTER_DAYS,
                "$frequency resolves to $minimumDays days, below the ${SQL_PREFILTER_DAYS}d cutoff the job's " +
                    "SQL pre-filter assumes — shorten the cutoff or this tier is skipped for up to a week",
            )
        }
    }

    private companion object {
        const val SHORTEST_MONTH_DAYS = 28L
        const val SQL_PREFILTER_DAYS = 7L
    }
}
