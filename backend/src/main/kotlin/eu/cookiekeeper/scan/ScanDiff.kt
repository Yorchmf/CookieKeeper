package eu.cookiekeeper.scan

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

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
        fun baseline(currentTrackerCount: Int): ScanDiff =
            ScanDiff(
                previousScanId = null,
                previousScanAt = null,
                addedCookieNames = emptyList(),
                removedCookieNames = emptyList(),
                currentTrackerCount = currentTrackerCount,
                previousTrackerCount = null,
            )

        /** The directional difference between a current and a previous scan, compared by cookie name. */
        fun between(
            currentCookieNames: Collection<String>,
            currentTrackerCount: Int,
            previous: PreviousScan,
        ): ScanDiff {
            val current = currentCookieNames.toSet()
            val previousNames = previous.cookieNames.toSet()
            return ScanDiff(
                previousScanId = previous.scanId,
                previousScanAt = previous.scanAt,
                addedCookieNames = (current - previousNames).sorted(),
                removedCookieNames = (previousNames - current).sorted(),
                currentTrackerCount = currentTrackerCount,
                previousTrackerCount = previous.trackerCount,
            )
        }
    }
}

/**
 * The previous completed scan's findings, as [ScanDiff.between] needs them: which scan it was, when it
 * ran, and its cookie names + tracker count to diff against. Keeps the diff a pure value type — the
 * calculator maps a [ScanEntity] into this, [ScanDiff] itself never sees an entity.
 */
data class PreviousScan(
    val scanId: UUID,
    val scanAt: Instant,
    val cookieNames: Collection<String>,
    val trackerCount: Int,
)

/**
 * Computes a [ScanDiff] for a completed scan against the previous completed scan of the same site. The
 * "previous = strictly-older `done` scan" invariant and the compare-by-name rule live here, so the email
 * send-gate ([ScanCompletionNotifier]) and the dashboard read path ([ScanQueryService]) can never drift
 * apart on what "changed" means.
 *
 * The caller passes in the current scan's cookie names and tracker count because it already holds them
 * (the notifier from the email payload, the query service from the detail response), so this only issues
 * the extra reads for the *previous* scan.
 */
@Service
class ScanDiffCalculator(
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
) {
    fun forScan(
        scan: ScanEntity,
        currentCookieNames: Collection<String>,
        currentTrackerCount: Int,
    ): ScanDiff {
        // Strictly older than this scan's own created_at: the callers run once the scan is already `done`,
        // so an unbounded "latest done" query would hand back the very scan we are diffing. Rides
        // idx_scans_site_id_created_at (V7).
        val previous =
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                scan.siteId,
                ScanStatus.DONE,
                scan.createdAt,
            ) ?: return ScanDiff.baseline(currentTrackerCount)
        val previousNames = scanCookieRepository.findByScanId(previous.id).map { it.name }
        return ScanDiff.between(
            currentCookieNames = currentCookieNames,
            currentTrackerCount = currentTrackerCount,
            previous =
                PreviousScan(
                    scanId = previous.id,
                    scanAt = previous.createdAt,
                    cookieNames = previousNames,
                    trackerCount = previous.marketingTrackerCount ?: 0,
                ),
        )
    }
}
