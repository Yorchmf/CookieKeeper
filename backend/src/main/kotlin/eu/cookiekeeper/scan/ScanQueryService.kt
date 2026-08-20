package eu.cookiekeeper.scan

import eu.cookiekeeper.scan.dto.ScanDetailResponse
import eu.cookiekeeper.scan.dto.ScanSummaryResponse
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Read side of the scanner: exposes a site's scan history and a single scan's classified cookies to the
 * dashboard. Every read is gated on site ownership first (`findByIdAndUserId`) so another user's site id
 * is indistinguishable from a true miss — ownership enforcement and anti-enumeration in one, matching
 * [eu.cookiekeeper.site.SiteService].
 */
@Service
class ScanQueryService(
    private val siteRepository: SiteRepository,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val scanDiffCalculator: ScanDiffCalculator,
    private val blockingVerificationService: BlockingVerificationService,
    private val clock: Clock,
) {
    /** Newest-first scan history for a site the caller owns, bounded to a sane page size. */
    fun list(
        userId: UUID,
        siteId: UUID,
        limit: Int,
    ): List<ScanSummaryResponse> {
        requireOwnedSite(userId, siteId)
        val bounded = limit.coerceIn(1, MAX_LIMIT)
        val scans = scanRepository.findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, bounded))
        val newCookieCounts = newCookieCountsWithinPage(scans)
        return scans.map { ScanSummaryResponse.from(it, newCookieCounts[it.id]) }
    }

    /** A single scan (owned, and belonging to [siteId]) with its cookies grouped for the results UI. */
    fun get(
        userId: UUID,
        siteId: UUID,
        scanId: UUID,
    ): ScanDetailResponse {
        requireOwnedSite(userId, siteId)
        val scan = scanRepository.findByIdAndSiteId(scanId, siteId) ?: throw ScanNotFoundException()
        val cookies = scanCookieRepository.findByScanId(scanId)
        // Only a completed scan has a meaningful diff; for the rest the previous-scan lookup would be
        // wasted work. The calculator does the precise cross-page previous-scan comparison.
        val diff =
            if (scan.status == ScanStatus.DONE) {
                scanDiffCalculator.forScan(scan, ScanFindings.of(cookies, scan.marketingTrackerCount ?: 0))
            } else {
                null
            }
        // A pure projection of this scan's own probe columns — never the site's current alert state, or an
        // old scan's page would report today's problem as if the crawl had found it.
        return ScanDetailResponse.from(scan, cookies, clock.instant(), diff, blockingVerificationService.verify(scan))
    }

    /**
     * New-cookie counts per `done` scan, comparing each to the next-older `done` scan WITHIN this page. One
     * extra query — all cookies for the page's completed scans via [ScanCookieRepository.findByScanIdIn] —
     * deliberately not the precise cross-page lookup the detail view does, so the history list stays at two
     * reads instead of an N+1 over the page. The oldest `done` scan on the page has no in-page baseline and
     * is therefore left out (rendered without a badge) rather than reported as "all new"; opening it shows
     * the true diff against its real predecessor.
     */
    private fun newCookieCountsWithinPage(scans: List<ScanEntity>): Map<UUID, Int> {
        val doneScans = scans.filter { it.status == ScanStatus.DONE }
        if (doneScans.size < 2) return emptyMap()
        val namesByScan =
            scanCookieRepository
                .findByScanIdIn(doneScans.map { it.id })
                .groupBy({ it.scanId }, { it.name })
        // `scans` is newest-first; walk oldest-first so each `done` scan is diffed against the prior `done`
        // baseline. `zipWithNext` naturally leaves the oldest scan out (it has no in-page predecessor).
        return doneScans
            .asReversed()
            .map { it.id to namesByScan[it.id].orEmpty().toSet() }
            .zipWithNext { (_, previousNames), (id, names) -> id to (names - previousNames).size }
            .toMap()
    }

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ) {
        siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
