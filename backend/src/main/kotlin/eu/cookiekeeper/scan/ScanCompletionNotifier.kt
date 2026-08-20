package eu.cookiekeeper.scan

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.notify.BestEffortEmailDelivery
import eu.cookiekeeper.notify.NotificationPreferenceService
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Everything a scan-complete email needs that lives outside the scan itself, resolved together so the
 * send path can't half-fail on a deleted site or owner. The domain is copied off the site rather than
 * the whole entity carried, because that is the only field the email uses.
 */
private data class ScanEmailTarget(
    val scan: ScanEntity,
    val domain: String,
    val user: UserEntity,
)

/**
 * Decides whether a completed scan is worth an email, and if so composes and delivers it. The
 * recipient's address and locale are resolved fresh from the user row (never carried on the event),
 * and delivery goes through the shared [BestEffortEmailDelivery] contract, so a broken mail provider
 * can never affect the scan pipeline. A missing site or user (deleted between the crawl and the send)
 * is logged by id and skipped, never thrown.
 *
 * ## What gets an email, and why
 *
 * Not every completed scan does — a nightly re-scan fleet that mails on every run would train users
 * to filter us out, and on the weekly Business cadence a 10-site account would get 10 mails a week
 * saying "nothing changed":
 *
 *  - [ScanTrigger.SITE_ADDED] — always. This is the activation moment: the first scan result is the
 *    product's first visible value, and it is the nudge to embed the widget.
 *  - [ScanTrigger.SCHEDULED] — only when the findings actually changed since the previous completed
 *    scan. That difference IS the paid value proposition ("we watch your site for new trackers"), and
 *    it is the only moment the customer has something to act on. An unchanged re-scan is silent.
 *  - [ScanTrigger.MANUAL] — never. The user clicked "Re-scan now" and is sitting on the dashboard
 *    watching the row; an email about what they are already looking at is noise.
 *
 * Archived sites are skipped: the customer has retired that domain and does not want mail about it.
 */
@Service
class ScanCompletionNotifier(
    private val composer: ScanEmailComposer,
    private val delivery: BestEffortEmailDelivery,
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val scanDiffCalculator: ScanDiffCalculator,
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
    private val notificationPreferences: NotificationPreferenceService,
) {
    private val log = LoggerFactory.getLogger(ScanCompletionNotifier::class.java)

    fun sendScanCompleted(
        scanId: UUID,
        siteId: UUID,
        trigger: ScanTrigger,
    ) {
        if (trigger == ScanTrigger.MANUAL) return
        val target = resolveTarget(scanId, siteId) ?: return

        // The owner's opt-out, checked after the site/owner resolve so we never read preferences for an
        // archived or deleted account. SITE_ADDED maps to the "first scan finished" toggle, SCHEDULED to
        // the "new trackers found" toggle; MANUAL already returned above.
        if (!wantsEmail(target.user.id, trigger)) {
            log.debug("Owner opted out of {} scan emails; skipping scan {}", trigger, scanId)
            return
        }

        val cookies = scanCookieRepository.findByScanId(scanId)
        val trackerCount = target.scan.marketingTrackerCount ?: 0
        // The send gate for monitoring scans: only mail when the findings actually moved since the previous
        // completed scan. The same [ScanDiffCalculator] the dashboard reads decides "changed" here, so the
        // email and the on-screen diff can never disagree about what counts as new.
        if (trigger == ScanTrigger.SCHEDULED &&
            !scanDiffCalculator.forScan(target.scan, ScanFindings.of(cookies, trackerCount)).hasNewFindings
        ) {
            log.debug("Scheduled scan {} found nothing new; no email", scanId)
            return
        }

        val summary =
            ScanSummary(
                siteId = siteId,
                scanId = scanId,
                domain = target.domain,
                cookieCount = cookies.size,
                marketingTrackerCount = trackerCount,
            )
        val user = target.user
        delivery.deliver(user.id, user.email, composer.scanCompletedEmail(user.locale, summary), "scan-completed")
    }

    /**
     * Whether the owner still wants this class of scan email. The two triggers that reach here map to the
     * two customer-facing toggles: SITE_ADDED is "your first scan finished" ([NotificationPreferences.scanComplete]),
     * SCHEDULED is "a monitoring scan found new trackers" ([NotificationPreferences.scanChanges]). An account
     * that never touched the settings page has no row and gets the all-on default, so silence here is always
     * an explicit choice, never a missing default.
     */
    private fun wantsEmail(
        userId: UUID,
        trigger: ScanTrigger,
    ): Boolean {
        val preferences = notificationPreferences.get(userId)
        return when (trigger) {
            ScanTrigger.SITE_ADDED -> preferences.scanComplete
            ScanTrigger.SCHEDULED -> preferences.scanChanges
            // Unreachable: MANUAL short-circuits at the top of sendScanCompleted. Mapped explicitly so a
            // future trigger cannot silently fall through to "send".
            ScanTrigger.MANUAL -> false
        }
    }

    /**
     * The scan and the person to mail about it, or null when there is nobody to mail. Everything this
     * listener needs is re-read here rather than carried on the event, because the event fires
     * asynchronously after commit and the account may have moved on in between.
     *
     * An archived site returns null silently — the customer retired that domain on purpose. A missing
     * row is logged: the scan, site or owner was deleted while the crawl was in flight, which is
     * legitimate but worth a line when someone asks why an email never arrived.
     */
    private fun resolveTarget(
        scanId: UUID,
        siteId: UUID,
    ): ScanEmailTarget? {
        val scan = scanRepository.findById(scanId).orElse(null) ?: return noRecipient("no scan", scanId)
        val site = siteRepository.findById(siteId).orElse(null) ?: return noRecipient("no site", siteId)
        if (site.status != SiteStatus.ACTIVE) return null
        val user = userRepository.findById(site.userId).orElse(null) ?: return noRecipient("no owner for site", siteId)
        return ScanEmailTarget(scan = scan, domain = site.domain, user = user)
    }

    /**
     * Log why no email is going out and yield null, so each lookup above stays a one-line elvis guard
     * instead of a five-line null branch. The [Nothing] return type says it never yields anything else.
     */
    private fun noRecipient(
        reason: String,
        id: UUID,
    ): Nothing? {
        log.warn("Skipping scan-complete email: {} {}", reason, id)
        return null
    }
}
