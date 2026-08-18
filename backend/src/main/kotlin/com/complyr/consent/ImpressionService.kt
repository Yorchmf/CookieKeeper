package com.complyr.consent

import com.complyr.analytics.BannerImpressionRepository
import com.complyr.consent.dto.ImpressionRequest
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Records banner impressions as a disposable per-site, per-day counter (Track 4 Slice D). The denominator
 * behind the dashboard's interaction rate: how many times the banner was shown, against how many consent
 * decisions followed.
 *
 * Deliberately NOT the consent-ingestion path (CLAUDE.md #3): this writes no audit evidence and no personal
 * data. The site key is validated exactly like consent ([ConsentService]) — resolve to an ACTIVE site or 404,
 * reusing [UnknownSiteException] — then a bare `(site_id, day)` counter is UPSERTed. The day is the server's
 * own UTC calendar day (never a client-sent time), matching how the consent trend buckets `created_at`, so the
 * interaction rate's numerator and denominator agree on what "a day" is. No IP, visitor id, or user-agent is
 * read or stored; the client IP is only an in-memory rate-limit bucket key upstream (RateLimitFilter).
 */
@Service
class ImpressionService(
    private val siteRepository: SiteRepository,
    private val bannerImpressionRepository: BannerImpressionRepository,
    private val clock: Clock,
) {
    @Transactional
    fun record(request: ImpressionRequest) {
        // Same public-key validation as consent: an unknown or archived key is a 404 with no enumeration
        // signal (keys are public). A beacon for a real-but-inactive site records nothing.
        val site =
            siteRepository.findBySiteKeyAndStatus(request.siteKey, SiteStatus.ACTIVE)
                ?: throw UnknownSiteException()
        bannerImpressionRepository.increment(site.id, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
    }
}
