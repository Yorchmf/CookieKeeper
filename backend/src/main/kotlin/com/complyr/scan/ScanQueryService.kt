package com.complyr.scan

import com.complyr.scan.dto.ScanDetailResponse
import com.complyr.scan.dto.ScanSummaryResponse
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Read side of the scanner: exposes a site's scan history and a single scan's classified cookies to the
 * dashboard. Every read is gated on site ownership first (`findByIdAndUserId`) so another user's site id
 * is indistinguishable from a true miss — ownership enforcement and anti-enumeration in one, matching
 * [com.complyr.site.SiteService].
 */
@Service
class ScanQueryService(
    private val siteRepository: SiteRepository,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
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
        return scanRepository
            .findBySiteIdOrderByCreatedAtDesc(siteId, PageRequest.of(0, bounded))
            .map(ScanSummaryResponse::from)
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
        return ScanDetailResponse.from(scan, cookies, clock.instant())
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
