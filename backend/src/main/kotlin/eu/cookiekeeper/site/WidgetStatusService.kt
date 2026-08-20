package eu.cookiekeeper.site

import eu.cookiekeeper.analytics.BannerImpressionRepository
import eu.cookiekeeper.site.dto.WidgetStatusResponse
import eu.cookiekeeper.site.dto.WidgetStatusState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * "Is my banner actually live?" — the feedback that was missing after a customer pasted the embed snippet.
 *
 * Distinct from [SiteVerificationService], which is a one-shot *activation gate*: verification proves the
 * customer controls the domain (snippet fetch or DNS TXT) and, once passed, never runs again. This answers
 * the different, ongoing question of whether the widget is still rendering to real visitors — a site can be
 * verified and have had its snippet removed a month later, and vice versa (DNS-verified, never installed).
 *
 * The signal is the banner-impression counter the widget already feeds (Track 4 Slice D, V26). Reusing it
 * is why this feature adds no migration, no column and nothing at all to the ingestion hot path — but it
 * also bounds what we may claim, in two ways the UI copy must respect:
 *
 *  1. **Day granularity.** The counter stores a UTC calendar day, never a finer timestamp (a deliberate
 *     privacy property of V26). So the honest statement is "seen today", not "seen 2 minutes ago".
 *  2. **Only new visitors are counted.** The widget returns early on a stored consent, so a returning
 *     visitor renders no banner and fires no beacon. Silence therefore means "no *undecided* visitor
 *     arrived", which is not the same as "the widget is gone" — hence [WidgetStatusState.IDLE] rather than
 *     a "broken" verdict, and copy that offers both explanations.
 *
 * Ownership-gated first (`findByIdAndUserId`), so another account's site id is a 404 indistinguishable
 * from a true miss — matching every other per-site read. Read-only: it never writes.
 */
@Service
class WidgetStatusService(
    private val siteRepository: SiteRepository,
    private val bannerImpressionRepository: BannerImpressionRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun status(
        userId: UUID,
        siteId: UUID,
    ): WidgetStatusResponse {
        siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

        // The server's own UTC day, matching how ingestion stamps the counter — so "today" here is the
        // same today the beacon wrote, whatever timezone the customer is reading the dashboard from.
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        // Inclusive of today: a 7-day window is today plus the six days before it, so a site whose only
        // impression is from this morning is ACTIVE rather than sitting one day outside its own window.
        val windowStart = today.minusDays(ACTIVE_WINDOW_DAYS - 1)
        val activity = bannerImpressionRepository.widgetActivity(siteId, today, windowStart)

        val state =
            when {
                activity.lastDay == null -> WidgetStatusState.NEVER_SEEN
                !activity.lastDay.isBefore(windowStart) -> WidgetStatusState.ACTIVE
                else -> WidgetStatusState.IDLE
            }

        return WidgetStatusResponse(
            state = state.wireValue,
            lastSeenDay = activity.lastDay,
            impressionsToday = activity.today,
            impressionsInWindow = activity.window,
            windowDays = ACTIVE_WINDOW_DAYS,
        )
    }

    companion object {
        /**
         * How recently an impression must have landed for the widget to read as live. A week is long
         * enough that a quiet weekend or a low-traffic small-business site doesn't flip to IDLE, and short
         * enough that a snippet removed a fortnight ago stops being reported as fine.
         */
        const val ACTIVE_WINDOW_DAYS = 7L
    }
}
