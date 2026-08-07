package com.complyr.scan

import com.complyr.common.ApiException
import com.complyr.scan.dto.PublicScanReportRequest
import com.complyr.scan.dto.PublicScanReportResponse
import com.complyr.scan.dto.PublicScanTeaserResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * A token, not a resource, is the read capability, so this is deliberately generic: an unknown token,
 * a honeypot's throwaway token (never persisted — see [PublicScanService]), and a legitimately expired
 * token all resolve to the SAME "not found or expired" outcome. That identical shape is what keeps a
 * fresh honeypot token from becoming a bot-detection oracle (docs anonymous-scan-funnel.md §9): a
 * trapped bot polling the token it was handed cannot tell it apart from a real result that has aged out.
 */
class PublicScanNotFoundException :
    ApiException(
        status = HttpStatus.NOT_FOUND,
        code = "PUBLIC_SCAN_NOT_FOUND",
        message = "Scan not found or expired",
    )

/**
 * The public read side of the anonymous free-scan funnel — token-scoped, never ownership-scoped
 * (there is no owner; the unguessable [PublicScanEntity.publicToken] is the whole authorization).
 *
 * Two tiers of disclosure (docs §9 decision #3): [teaser] is free and coarse (counts only), while
 * [unlockReport] captures the visitor's email and returns the full per-cookie detail. The email is a
 * lead — persisted immutably via `copy(...)` + save, never logged (PII, CLAUDE.md #4).
 */
@Service
class PublicScanReadService(
    private val publicScanRepository: PublicScanRepository,
    private val publicScanCookieRepository: PublicScanCookieRepository,
    private val clock: Clock,
) {
    /** Free, ungated headline verdict. Cookie-level detail is withheld until [unlockReport]. */
    @Transactional(readOnly = true)
    fun teaser(token: String): PublicScanTeaserResponse {
        val scan = liveScan(token)
        return PublicScanTeaserResponse.from(scan, cookiesFor(scan), clock.instant())
    }

    /**
     * Email gate: record the lead on the scan row (idempotent overwrite) and return the detailed
     * report. The email is captured even when the scan is not yet `done` (a lead is worth keeping);
     * the returned [PublicScanReportResponse.status] tells the caller whether the detail is populated.
     */
    @Transactional
    fun unlockReport(
        token: String,
        request: PublicScanReportRequest,
    ): PublicScanReportResponse {
        val scan = liveScan(token)
        val updated = publicScanRepository.save(scan.copy(email = request.email, updatedAt = clock.instant()))
        return PublicScanReportResponse.from(updated, cookiesFor(updated), clock.instant())
    }

    /** Resolve a token to a live scan, or fail generically if it is unknown or past its retention TTL. */
    private fun liveScan(token: String): PublicScanEntity {
        val scan = publicScanRepository.findByPublicToken(token) ?: throw PublicScanNotFoundException()
        if (!clock.instant().isBefore(scan.expiresAt)) throw PublicScanNotFoundException()
        return scan
    }

    /** Findings exist only once the crawl has completed; a not-yet-done scan reports none. */
    private fun cookiesFor(scan: PublicScanEntity): List<PublicScanCookieEntity> =
        if (scan.status == ScanStatus.DONE) publicScanCookieRepository.findByPublicScanId(scan.id) else emptyList()
}
