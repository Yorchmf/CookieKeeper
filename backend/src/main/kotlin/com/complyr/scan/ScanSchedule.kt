package com.complyr.scan

import com.complyr.billing.AccountEntitlement
import com.complyr.billing.EntitlementService
import com.complyr.billing.RescanFrequency
import com.complyr.scan.dto.ScanScheduleResponse
import com.complyr.scan.dto.UnscheduledReason
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * THE definition of when a site becomes due for its next scheduled re-scan. [ScheduledRescanJob] gates its
 * nightly enqueue on it and [ScanScheduleService] renders it to the customer, so the date the dashboard
 * promises and the night the job actually fires cannot drift apart.
 *
 * The cadence is a [java.time.Period] (calendar weeks/months), which an [Instant] cannot add directly, so
 * it is applied in the scheduler's zone.
 */
object RescanCadence {
    fun dueAt(
        lastScanAt: Instant,
        frequency: RescanFrequency,
        zone: ZoneId,
    ): Instant = lastScanAt.atZone(zone).plus(frequency.interval).toInstant()
}

/**
 * Answers "when does this site get scanned again?" for the dashboard's scan-history card. Read-only, and
 * ownership-gated first (`findByIdAndUserId`) so another user's site id is indistinguishable from a miss,
 * matching [ScanQueryService].
 *
 * Every branch mirrors a condition [ScheduledRescanJob] actually applies, rather than restating the
 * pricing table: the job only considers ACTIVE sites ([SiteRepository.findRescanCandidates]), it skips
 * lapsed accounts, and it measures "last scan" as the newest scan of any status. It also refuses to name a
 * date that falls beyond a running trial, because the account is Expired by then and the job skips it.
 * Promising a date the job would not honour is worse than promising nothing.
 */
@Service
class ScanScheduleService(
    private val siteRepository: SiteRepository,
    private val scanRepository: ScanRepository,
    private val entitlementService: EntitlementService,
    private val clock: Clock,
) {
    fun forSite(
        userId: UUID,
        siteId: UUID,
    ): ScanScheduleResponse {
        val site = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
        // Archived sites are filtered out of the candidate query, so the plan never enters into it — check
        // before resolving the entitlement rather than spending its two billing queries on a dead answer.
        if (site.status != SiteStatus.ACTIVE) {
            return ScanScheduleResponse.unscheduled(UnscheduledReason.ARCHIVED)
        }
        return forActiveSite(siteId, entitlementService.resolve(userId))
    }

    /** The plan-dependent half of the answer, once the site itself is known to be in the rotation. */
    private fun forActiveSite(
        siteId: UUID,
        entitlement: AccountEntitlement,
    ): ScanScheduleResponse {
        // An Expired account is skipped by ScheduledRescanJob.isDue ("no new sites, no scans"). Note
        // EXPIRED_ENTITLEMENTS still carries a MONTHLY cadence so the shape stays uniform — surfacing it
        // would advertise a scan that never runs.
        if (entitlement is AccountEntitlement.Expired) {
            return ScanScheduleResponse.unscheduled(UnscheduledReason.LAPSED)
        }
        val frequency = entitlement.entitlements.rescanFrequency
        // Null when the site has never been scanned: the job treats that as due immediately
        // (`lastScanAt ?: return true`), so there is no future date to state.
        val dueAt = lastScanAt(siteId)?.let { RescanCadence.dueAt(it, frequency, clock.zone) }
        // A trial borrows Starter's MONTHLY cadence but only runs for two weeks, so a site scanned on
        // signup day comes due well after the account has resolved to Expired — the job would skip it.
        // Every new signup would otherwise be shown a firm date for a scan that never happens.
        val trialEndsFirst =
            entitlement is AccountEntitlement.Trial && dueAt != null && !dueAt.isBefore(entitlement.endsAt)
        return if (trialEndsFirst) {
            ScanScheduleResponse.unscheduled(UnscheduledReason.TRIAL_ENDS_FIRST)
        } else {
            ScanScheduleResponse.scheduled(frequency, dueAt)
        }
    }

    /**
     * The site's most recent scan instant, of ANY status. The job keys due-ness off `max(created_at)`
     * across every scan row, so a queued or failed scan pushes the next scheduled one out just as a
     * completed one does — sharing the job's definition keeps that one rule rather than two.
     */
    private fun lastScanAt(siteId: UUID): Instant? = scanRepository.findFirstBySiteIdOrderByCreatedAtDesc(siteId)?.createdAt
}
