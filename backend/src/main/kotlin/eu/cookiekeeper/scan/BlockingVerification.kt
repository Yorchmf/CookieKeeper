package eu.cookiekeeper.scan

/**
 * The verdict of the post-install blocking verification (BACKLOG #19): does the widget the customer
 * installed actually stop the vendors on their site from firing before consent?
 *
 * This is answerable only because our crawl is a *before-consent* crawl. Nothing on the crawled page
 * ever clicks "Accept", so a vendor request we observe is a vendor request that happens with no
 * consent — and if our own embed is on the page, that vendor is provably **not tagged for blocking**.
 * A banner that makes a written claim the site does not honour is worse than no banner at all, which
 * is why this is scored `critical`.
 *
 * [UNKNOWN] is a first-class outcome, not a fallback: scans that predate the probe, scans that never
 * completed, and pages we could not question all land here, and the read layer says "not measured"
 * rather than inventing a pass or a fail.
 */
enum class BlockingStatus(
    /** Stable machine token on the wire; all wording is localized in the dashboard. */
    val token: String,
) {
    /** We did not measure it. Never rendered as a pass or a fail. */
    UNKNOWN("unknown"),

    /** No Complyr embed found on any crawled page — the widget is not installed (or not on these pages). */
    NOT_INSTALLED("not_installed"),

    /** An embed is present but carries a different site key — the site is configured as somebody else's. */
    WRONG_SITE_KEY("wrong_site_key"),

    /** The widget is installed and correct, and a consent-decidable vendor still fired before consent. */
    UNBLOCKED("unblocked"),

    /** The widget is installed, correct, and nothing decidable fired before consent. */
    CLEAN("clean"),
    ;

    /**
     * Whether this is a state the customer must act on *and* that the widget being installed makes our
     * business to nag about. [NOT_INSTALLED] is deliberately excluded: onboarding already owns "install
     * the widget", and a site that never installed it is not the failure mode this feature exists for.
     */
    val isUnresolved: Boolean
        get() = this == WRONG_SITE_KEY || this == UNBLOCKED
}

/**
 * One vendor that fired before consent, resolved from our own curated dataset — [domain] is the
 * dataset KEY we persisted, [name] its display name and [consentCategory] the consent category whose
 * tag the customer must add. Never an observed request host (§4: nothing attacker-controlled is
 * persisted or shown back).
 */
data class BlockingVendor(
    val domain: String,
    val name: String,
    val consentCategory: String,
)

/**
 * The full verdict for one scan: the [status], the vendors that need tagging, and how many scripts on
 * the page *were* correctly tagged ([blockedScriptCount], null when unmeasured) — the latter is what
 * lets the dashboard say "you tagged 3 scripts, you missed this one" instead of a bare accusation.
 */
data class BlockingVerification(
    val status: BlockingStatus,
    val vendors: List<BlockingVendor> = emptyList(),
    val blockedScriptCount: Int? = null,
) {
    companion object {
        val UNKNOWN = BlockingVerification(BlockingStatus.UNKNOWN)
    }
}

/**
 * Codec for `scans.observed_trackers`: the dataset keys of the consent-decidable vendors a crawl saw.
 * Comma-joined in one column rather than given a child table — the same trade `sites.consent_basis_categories`
 * (V27) makes, and for the same reason: this is a display-only projection of a replaceable scan finding,
 * not something anything joins or aggregates on.
 *
 * A non-null value means the probe ran; the empty string is a *measured* "none fired", which is why
 * [format] is only ever called on a completed crawl and [parse] treats null and empty differently at
 * the call site.
 */
object ObservedTrackers {
    /** Upper bound on stored keys. A page firing more than this is already maximally non-compliant. */
    const val MAX_VENDORS = 25

    private const val SEPARATOR = ","

    fun format(domains: List<String>): String = domains.take(MAX_VENDORS).joinToString(SEPARATOR)

    fun parse(stored: String?): List<String> =
        stored
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
