package eu.cookiekeeper.scan

import eu.cookiekeeper.banner.ConsentCategory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * What one completed scan found, reduced to the dimensions anything downstream compares on: the cookie
 * NAMES, the marketing tracker COUNT, and which consent categories those findings put *in use*.
 *
 * This is the single description of a scan's findings — [ScanDiff] compares two of them, and the
 * consent-basis check ([eu.cookiekeeper.site.ConsentBasisService]) reads [categoriesInUse] off one.
 * Neither derives its own notion of "what this scan found".
 */
data class ScanFindings(
    val cookieNames: Set<String>,
    val categoriesInUse: Set<String>,
    val trackerCount: Int,
) {
    companion object {
        /**
         * Only the categories a visitor can actually decide. `necessary` is excluded on purpose: it is
         * required, so no consent choice can reject it and its arrival can never invalidate a consent
         * that was collected earlier.
         */
        private val DECIDABLE_KEYS: Set<String> =
            ConsentCategory.entries
                .filterNot { it.required }
                .map { it.key }
                .toSet()

        /**
         * Reduce a scan's persisted cookies (plus its tracker count) to the findings. Unclassified
         * cookies contribute nothing — a signature miss means we do not know what the cookie is for,
         * and guessing would re-prompt visitors on noise. A non-zero tracker count means marketing is
         * in use even when no marketing *cookie* was recorded: third-party marketing hosts are counted,
         * never stored ([ScanEntity.marketingTrackerCount]).
         */
        fun of(
            cookies: Collection<ScanCookieEntity>,
            trackerCount: Int,
        ): ScanFindings =
            ScanFindings(
                cookieNames = cookies.mapTo(mutableSetOf()) { it.name },
                categoriesInUse =
                    buildSet {
                        cookies.forEach { cookie -> cookie.category?.takeIf { it in DECIDABLE_KEYS }?.let(::add) }
                        if (trackerCount > 0) add(ConsentCategory.MARKETING.key)
                    },
                trackerCount = trackerCount,
            )
    }
}

/**
 * How a completed scan's findings differ from the previous completed scan of the same site — the
 * "what changed since last time" both the scan-complete email and the dashboard build on.
 *
 * Cookies are compared by NAME, never by row identity: every scan writes its own `scan_cookies` rows
 * (a re-run deletes + reinserts), so a row-level comparison would report a change on every single run.
 * Marketing trackers are compared by count only — raw hosts are never stored ([ScanEntity.marketingTrackerCount]).
 *
 * [previousScanId] is null when this is the site's first completed scan: there is no baseline, so the
 * added/removed lists are empty and the UI shows no comparison. Note that a missing baseline still counts
 * as [hasNewFindings] (there is nothing to be quiet about), which is why the two are distinct.
 */
data class ScanDiff(
    val previousScanId: UUID?,
    val previousScanAt: Instant?,
    val addedCookieNames: List<String>,
    val removedCookieNames: List<String>,
    val currentTrackerCount: Int,
    val previousTrackerCount: Int?,
) {
    val hasPrevious: Boolean get() = previousScanId != null
    val newCookieCount: Int get() = addedCookieNames.size
    val removedCookieCount: Int get() = removedCookieNames.size

    /** Signed change in distinct marketing trackers since the previous scan, or null with no baseline. */
    val trackerCountDelta: Int? get() = previousTrackerCount?.let { currentTrackerCount - it }

    /**
     * Whether this scan found something the previous completed scan did not — the send gate for
     * [ScanCompletionNotifier]'s scheduled-scan emails. A missing baseline counts as changed; a different
     * tracker count counts even when the cookie names are identical; and a *disappearing* cookie counts too
     * (a tracker vanishing is a policy-affecting change the customer's cookie policy needs to reflect).
     */
    val hasNewFindings: Boolean
        get() =
            !hasPrevious ||
                previousTrackerCount != currentTrackerCount ||
                addedCookieNames.isNotEmpty() ||
                removedCookieNames.isNotEmpty()

    companion object {
        /** A scan with no earlier completed scan to compare against. */
        fun baseline(current: ScanFindings): ScanDiff =
            ScanDiff(
                previousScanId = null,
                previousScanAt = null,
                addedCookieNames = emptyList(),
                removedCookieNames = emptyList(),
                currentTrackerCount = current.trackerCount,
                previousTrackerCount = null,
            )

        /** The directional difference between a current and a previous scan, compared by cookie name. */
        fun between(
            current: ScanFindings,
            previous: PreviousScan,
        ): ScanDiff =
            ScanDiff(
                previousScanId = previous.scanId,
                previousScanAt = previous.scanAt,
                addedCookieNames = (current.cookieNames - previous.findings.cookieNames).sorted(),
                removedCookieNames = (previous.findings.cookieNames - current.cookieNames).sorted(),
                currentTrackerCount = current.trackerCount,
                previousTrackerCount = previous.findings.trackerCount,
            )
    }
}

/**
 * The previous completed scan, as [ScanDiff.between] needs it: which scan it was, when it ran, and what
 * it found. Keeps the diff a pure value type — the calculator maps a [ScanEntity] and its cookie rows
 * into this, [ScanDiff] itself never sees an entity.
 */
data class PreviousScan(
    val scanId: UUID,
    val scanAt: Instant,
    val findings: ScanFindings,
)

/**
 * Computes a [ScanDiff] for a completed scan against the previous completed scan of the same site. The
 * "previous = strictly-older `done` scan" invariant and the compare-by-name rule live here, so the email
 * send-gate ([ScanCompletionNotifier]) and the dashboard read path ([ScanQueryService]) can never drift
 * apart on what "changed" means.
 *
 * The caller passes in the current scan's [ScanFindings] because it already holds the cookie rows (the
 * notifier from the email payload, the query service from the detail response), so this only issues the
 * extra reads for the *previous* scan.
 */
@Service
class ScanDiffCalculator(
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
) {
    fun forScan(
        scan: ScanEntity,
        current: ScanFindings,
    ): ScanDiff {
        // Strictly older than this scan's own created_at: the callers run once the scan is already `done`,
        // so an unbounded "latest done" query would hand back the very scan we are diffing. Rides
        // idx_scans_site_id_created_at (V7).
        val previous =
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                scan.siteId,
                ScanStatus.DONE,
                scan.createdAt,
            ) ?: return ScanDiff.baseline(current)
        val previousCookies = scanCookieRepository.findByScanId(previous.id)
        return ScanDiff.between(
            current = current,
            previous =
                PreviousScan(
                    scanId = previous.id,
                    scanAt = previous.createdAt,
                    findings = ScanFindings.of(previousCookies, previous.marketingTrackerCount ?: 0),
                ),
        )
    }
}
