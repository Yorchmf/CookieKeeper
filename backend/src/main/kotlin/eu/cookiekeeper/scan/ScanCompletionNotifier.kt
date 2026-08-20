package eu.cookiekeeper.scan

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.notify.BestEffortEmailDelivery
import eu.cookiekeeper.notify.NotificationPreferenceService
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Everything a scan-complete email needs that lives outside the scan itself, resolved together so the
 * send path can't half-fail on a deleted site or owner.
 */
data class ScanEmailTarget(
    val scan: ScanEntity,
    val site: SiteEntity,
    val user: UserEntity,
) {
    val domain: String get() = site.domain
}

/**
 * Resolves the three rows a scan email is about. Split out from [ScanCompletionNotifier] so the notifier
 * holds one collaborator instead of three repositories, and so "who are we mailing, and are they still
 * there?" is answerable on its own.
 *
 * Everything is re-read here rather than carried on the [ScanCompleted] event, because the event fires
 * asynchronously after commit and the account may have moved on in between.
 */
@Service
class ScanEmailTargetResolver(
    private val scanRepository: ScanRepository,
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(ScanEmailTargetResolver::class.java)

    /**
     * The scan and the person to mail about it, or null when there is nobody to mail.
     *
     * An archived site returns null silently — the customer retired that domain on purpose. A missing
     * row is logged: the scan, site or owner was deleted while the crawl was in flight, which is
     * legitimate but worth a line when someone asks why an email never arrived.
     */
    fun resolve(
        scanId: UUID,
        siteId: UUID,
    ): ScanEmailTarget? {
        val scan = scanRepository.findById(scanId).orElse(null) ?: return noRecipient("no scan", scanId)
        val site = siteRepository.findById(siteId).orElse(null) ?: return noRecipient("no site", siteId)
        if (site.status != SiteStatus.ACTIVE) return null
        val user = userRepository.findById(site.userId).orElse(null) ?: return noRecipient("no owner for site", siteId)
        return ScanEmailTarget(scan = scan, site = site, user = user)
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
 * The one exception to "an unchanged re-scan is silent" is the post-install blocking nudge (BACKLOG #19):
 * a site whose widget is installed but demonstrably not blocking is *stably* non-compliant, so "nothing
 * changed" is the worst possible reason to stay quiet. That nudge rides this same schedule — no extra
 * job — and is rate-limited by the site's own streak rather than by the scan cadence.
 *
 * Archived sites are skipped: the customer has retired that domain and does not want mail about it.
 */
@Service
class ScanCompletionNotifier(
    private val composer: ScanEmailComposer,
    private val delivery: BestEffortEmailDelivery,
    private val targets: ScanEmailTargetResolver,
    private val scanCookieRepository: ScanCookieRepository,
    private val scanDiffCalculator: ScanDiffCalculator,
    private val blockingVerification: BlockingVerificationService,
    private val notificationPreferences: NotificationPreferenceService,
) {
    private val log = LoggerFactory.getLogger(ScanCompletionNotifier::class.java)

    fun sendScanCompleted(
        scanId: UUID,
        siteId: UUID,
        trigger: ScanTrigger,
    ) {
        val target = targets.resolve(scanId, siteId) ?: return

        // Streak bookkeeping runs for EVERY completed scan, before any email gate — including manual ones
        // we never mail about. A customer who fixes their blocking and hits "Re-scan now" has resolved it,
        // and the clock must know that even though nothing is sent.
        val verification = blockingVerification.verify(target.scan)
        blockingVerification.record(target.site, verification)

        if (mayEmail(target, trigger)) send(target, trigger, verification)
    }

    /**
     * Whether we are allowed to mail this account about this scan at all. MANUAL never mails — the user
     * clicked "Re-scan now" and is watching the row. Otherwise it is the owner's own opt-out, checked
     * after the site/owner resolve so we never read preferences for an archived or deleted account.
     */
    private fun mayEmail(
        target: ScanEmailTarget,
        trigger: ScanTrigger,
    ): Boolean {
        if (trigger == ScanTrigger.MANUAL) return false
        val wanted = wantsEmail(target.user.id, trigger)
        if (!wanted) log.debug("Owner opted out of {} scan emails; skipping scan {}", trigger, target.scan.id)
        return wanted
    }

    /** Pick and deliver the one email this scan warrants: the results, the blocking nudge, or nothing. */
    private fun send(
        target: ScanEmailTarget,
        trigger: ScanTrigger,
        verification: BlockingVerification,
    ) {
        val cookies = scanCookieRepository.findByScanId(target.scan.id)
        val trackerCount = target.scan.marketingTrackerCount ?: 0
        // The send gate for monitoring scans: only mail when the findings actually moved since the previous
        // completed scan. The same [ScanDiffCalculator] the dashboard reads decides "changed" here, so the
        // email and the on-screen diff can never disagree about what counts as new.
        if (trigger == ScanTrigger.SCHEDULED &&
            !scanDiffCalculator.forScan(target.scan, ScanFindings.of(cookies, trackerCount)).hasNewFindings
        ) {
            // Nothing new to report — but a site that has been failing its blocking check for weeks is
            // exactly what this silence hides, so the nudge rides the same cadence rather than adding a job.
            sendBlockingNudge(target, verification)
            return
        }

        val summary =
            ScanSummary(
                siteId = target.site.id,
                scanId = target.scan.id,
                domain = target.domain,
                cookieCount = cookies.size,
                marketingTrackerCount = trackerCount,
            )
        val user = target.user
        delivery.deliver(user.id, user.email, composer.scanCompletedEmail(user.locale, summary), "scan-completed")
    }

    /**
     * Send the "still not blocking" nudge if this site has one due (BACKLOG #19). Claimed only here, at
     * the point of sending: a claim stamps "we told them", so claiming on a path that then declines to
     * send would silence the site for a fortnight without a word ever reaching the customer.
     */
    private fun sendBlockingNudge(
        target: ScanEmailTarget,
        verification: BlockingVerification,
    ) {
        val nudge = blockingVerification.claimNudge(target.site, verification) ?: return
        val user = target.user
        val summary =
            BlockingAlertSummary(
                siteId = target.site.id,
                scanId = target.scan.id,
                domain = target.domain,
                daysUnresolved = nudge.daysUnresolved,
                vendorNames = nudge.verification.vendors.map { it.name },
                wrongSiteKey = nudge.verification.status == BlockingStatus.WRONG_SITE_KEY,
            )
        log.info(
            "Site {} still not blocking after {} day(s) ({}); sending nudge",
            target.site.id,
            nudge.daysUnresolved,
            nudge.verification.status.token,
        )
        delivery.deliver(user.id, user.email, composer.blockingAlertEmail(user.locale, summary), "blocking-alert")
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
            // Unreachable: MANUAL short-circuits in mayEmail. Mapped explicitly so a
            // future trigger cannot silently fall through to "send".
            ScanTrigger.MANUAL -> false
        }
    }
}
