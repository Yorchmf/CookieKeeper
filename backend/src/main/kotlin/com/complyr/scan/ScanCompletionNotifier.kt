package com.complyr.scan

import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import com.complyr.notify.BestEffortEmailDelivery
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
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
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(ScanCompletionNotifier::class.java)

    fun sendScanCompleted(
        scanId: UUID,
        siteId: UUID,
        trigger: ScanTrigger,
    ) {
        if (trigger == ScanTrigger.MANUAL) return
        val target = resolveTarget(scanId, siteId) ?: return

        val cookies = scanCookieRepository.findByScanId(scanId)
        val trackerCount = target.scan.marketingTrackerCount ?: 0
        if (trigger == ScanTrigger.SCHEDULED && !hasNewFindings(target.scan, cookies, trackerCount)) {
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

    /**
     * Whether [scan] found something the previous completed scan of the same site did not: a cookie name
     * that wasn't there before, or a different marketing-tracker count. Compared by NAME rather than row
     * identity because every scan writes its own `scan_cookies` rows — row-level comparison would report
     * a change every single run.
     *
     * A site with no earlier completed scan counts as changed (there is no baseline to be quiet about);
     * in practice the site-added scan is always that baseline. The lookup is bounded by this scan's own
     * `created_at` because the caller runs after the completion commit, so [scan] is itself `done` and a
     * plain "latest done scan" query would return the scan we are comparing against itself.
     *
     * Deliberately one-directional-ish: a *disappearing* cookie also changes the name set and so also
     * mails. That is intended — a tracker vanishing is a policy-affecting change the customer's cookie
     * policy needs to reflect.
     */
    private fun hasNewFindings(
        scan: ScanEntity,
        cookies: List<ScanCookieEntity>,
        trackerCount: Int,
    ): Boolean {
        val previous =
            scanRepository.findFirstBySiteIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                scan.siteId,
                ScanStatus.DONE,
                scan.createdAt,
            ) ?: return true
        if ((previous.marketingTrackerCount ?: 0) != trackerCount) return true
        val previousNames = scanCookieRepository.findByScanId(previous.id).mapTo(mutableSetOf()) { it.name }
        return cookies.mapTo(mutableSetOf()) { it.name } != previousNames
    }
}
