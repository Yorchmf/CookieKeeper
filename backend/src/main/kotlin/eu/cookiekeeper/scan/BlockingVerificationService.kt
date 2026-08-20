package eu.cookiekeeper.scan

import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A due "still unfixed" nudge for one site: the verdict to describe and how long it has stood.
 * [daysUnresolved] is what the email leads with — the customer is paying for a consent tool that
 * their site has not been honouring for that many days.
 */
data class BlockingNudge(
    val verification: BlockingVerification,
    val unresolvedSince: Instant,
    val daysUnresolved: Long,
)

/**
 * Turns a completed scan's raw probe columns into the customer-facing blocking verdict (BACKLOG #19),
 * and owns the site-level streak that decides when to nudge about one that is not getting fixed.
 *
 * Two deliberately separate jobs:
 *
 *  - [verify] is a **pure projection** of one scan row. It is what the scan detail page renders, so it
 *    must describe the scan it was asked about and nothing else — no current-site state leaks in, or an
 *    old scan's page would report today's problem.
 *  - [record] advances the streak on the *site*, and [claimNudge] answers whether a nudge is due.
 *
 * Those last two are deliberately separate calls rather than one. Recording must happen for **every**
 * completed scan, including the manual ones we never mail about — otherwise a customer who fixes their
 * blocking with a hand-triggered re-scan stays on a streak they already resolved. Claiming stamps the
 * "we told them" marker, so it may only happen when the mail is actually about to go out; folding the
 * two together would burn a due nudge on a scan whose email we then decline to send.
 *
 * The nudge fires only while the widget is installed ([BlockingStatus.isUnresolved]): telling someone
 * who never embedded the snippet that their blocking is broken is noise, and onboarding already asks
 * for the install.
 */
@Service
class BlockingVerificationService(
    private val trackerClassifier: TrackerClassifier,
    private val siteRepository: SiteRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(BlockingVerificationService::class.java)

    /**
     * The verdict for [scan]. [BlockingVerification.UNKNOWN] for anything we did not measure — a scan
     * that never completed, or one that predates the probe (all four columns null).
     */
    fun verify(scan: ScanEntity): BlockingVerification {
        if (scan.status != ScanStatus.DONE) return BlockingVerification.UNKNOWN
        val installed = scan.widgetDetected ?: return BlockingVerification.UNKNOWN
        val vendors = resolveVendors(scan.observedTrackers)
        return BlockingVerification(
            status = resolveStatus(installed, scan.widgetSiteKeyMatched, vendors),
            vendors = vendors,
            blockedScriptCount = scan.blockedScriptCount,
        )
    }

    /**
     * Fold [verification] into [site]'s unresolved-blocking streak. Call for **every** completed scan.
     *
     * A resolved (or unmeasurable) verdict closes the streak, so the clock always measures how long
     * *this* problem has stood rather than when we first ever saw one. Opening is guarded on `IS NULL`,
     * so a run of failing scans does not keep resetting it.
     */
    @Transactional
    fun record(
        site: SiteEntity,
        verification: BlockingVerification,
    ) {
        if (!verification.status.isUnresolved) {
            if (siteRepository.clearBlockingAlert(site.id) > 0) {
                log.info("Site {} blocking verification resolved ({})", site.id, verification.status.token)
            }
            return
        }
        if (site.blockingAlertSince != null) return
        if (siteRepository.startBlockingAlert(site.id, clock.instant()) > 0) {
            log.info(
                "Site {} blocking verification unresolved ({}, {} vendor(s)); nudge clock started",
                site.id,
                verification.status.token,
                verification.vendors.size,
            )
        }
    }

    /**
     * Claim the nudge for [site] if one is due — call only when the mail can actually be sent, because a
     * successful claim stamps "we told them". Due means: the streak has outlasted [GRACE], and either we
     * have never nudged or the last one is [REPEAT] old. The stamp is a compare-and-set on the value
     * [site] carries, so two scans finishing together can only claim once.
     *
     * [site] may be the snapshot taken before [record] ran: the only field that matters here is
     * `blockingAlertSince`, which [record] leaves untouched once set and only ever clears on a verdict
     * that would make this method return early anyway.
     */
    @Transactional
    fun claimNudge(
        site: SiteEntity,
        verification: BlockingVerification,
    ): BlockingNudge? {
        val since = dueSince(site, verification) ?: return null
        val now = clock.instant()
        if (siteRepository.markBlockingAlertNotified(site.id, now, site.blockingAlertNotifiedAt) == 0) {
            // Another completed scan for this site claimed the send between our read and our write.
            log.debug("Site {} blocking nudge claimed concurrently; skipping", site.id)
            return null
        }
        return BlockingNudge(
            verification = verification,
            unresolvedSince = since,
            daysUnresolved = Duration.between(since, now).toDays(),
        )
    }

    /**
     * When the current streak started, if a nudge is due for it right now — null when the verdict is
     * resolved, no streak is open, the streak is still inside [GRACE], or we nudged less than [REPEAT]
     * ago. One expression rather than a ladder of guards so the four conditions read as one rule.
     */
    private fun dueSince(
        site: SiteEntity,
        verification: BlockingVerification,
    ): Instant? {
        val since = site.blockingAlertSince
        val notifiedAt = site.blockingAlertNotifiedAt
        val now = clock.instant()
        val due =
            verification.status.isUnresolved &&
                since != null &&
                !now.isBefore(since.plus(GRACE)) &&
                (notifiedAt == null || !now.isBefore(notifiedAt.plus(REPEAT)))
        return since.takeIf { due }
    }

    /** Dataset keys back to display rows, dropping any a later dataset revision removed. */
    private fun resolveVendors(stored: String?): List<BlockingVendor> =
        trackerClassifier
            .describe(ObservedTrackers.parse(stored))
            .mapNotNull { signature ->
                signature.consentCategoryKey()?.let {
                    BlockingVendor(domain = signature.domain, name = signature.name, consentCategory = it)
                }
            }

    private fun resolveStatus(
        installed: Boolean,
        siteKeyMatched: Boolean?,
        vendors: List<BlockingVendor>,
    ): BlockingStatus =
        when {
            !installed -> BlockingStatus.NOT_INSTALLED
            siteKeyMatched == false -> BlockingStatus.WRONG_SITE_KEY
            vendors.isNotEmpty() -> BlockingStatus.UNBLOCKED
            else -> BlockingStatus.CLEAN
        }

    private companion object {
        /**
         * How long a site may be non-compliant before we say so. Long enough that someone who fixes it
         * the same week is never nagged, short enough that the site is not silently broken for a month.
         */
        val GRACE: Duration = Duration.ofDays(7)

        /** Minimum gap between nudges for one unbroken streak. */
        val REPEAT: Duration = Duration.ofDays(14)
    }
}
