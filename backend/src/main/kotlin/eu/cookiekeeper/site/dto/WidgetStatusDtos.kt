package eu.cookiekeeper.site.dto

import java.time.LocalDate

/**
 * Whether we are currently seeing this site's banner in the wild.
 *
 * Deliberately three states rather than a boolean "installed": the only evidence we have is the
 * impression beacon the widget fires when it *renders the banner*, and the banner is not rendered for a
 * visitor who already chose (the widget returns early on a stored consent). So an absence of beacons is
 * genuinely ambiguous — it can mean "not installed" or "no new visitors" — and the wire contract keeps
 * that ambiguity visible instead of resolving it into a wrong claim. See [eu.cookiekeeper.site.WidgetStatusService].
 */
enum class WidgetStatusState(
    val wireValue: String,
) {
    /** No impression has ever been recorded for this site — nothing has confirmed the install yet. */
    NEVER_SEEN("never_seen"),

    /** An impression landed within the status window: the widget is installed and rendering. */
    ACTIVE("active"),

    /** Impressions exist, but none inside the window. Ambiguous — see the enum doc. */
    IDLE("idle"),
}

/**
 * The site page's widget-status payload. Derived entirely from the existing per-day impression counter
 * (`banner_impressions`), so it carries the same day granularity: [lastSeenDay] is a UTC calendar day, not
 * a timestamp. [windowDays] is echoed so the dashboard's copy states the same window the backend applied
 * rather than hardcoding its own.
 */
data class WidgetStatusResponse(
    val state: String,
    val lastSeenDay: LocalDate?,
    val impressionsToday: Long,
    val impressionsInWindow: Long,
    val windowDays: Long,
)
