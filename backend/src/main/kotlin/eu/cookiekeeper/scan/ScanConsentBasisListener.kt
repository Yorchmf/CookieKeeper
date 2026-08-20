package eu.cookiekeeper.scan

import eu.cookiekeeper.site.ConsentBasisService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Keeps a site's consent basis in step with what its scans actually find (BACKLOG #18): every completed
 * crawl reports which consent-decidable categories are now in use, and [ConsentBasisService] decides
 * whether that is a change worth asking visitors about again.
 *
 * Runs AFTER_COMMIT for the same reason the scan email does — the `done` transition and the
 * `scan_cookies` rows must be visible to the fresh transaction this opens — and synchronously, because
 * it is two reads and at most one small update, and no visitor is waiting on it.
 *
 * Failures are logged, never rethrown: a scan that already committed must not be undone (a rolled-back
 * `markSucceeded` leaves the job leased and re-crawls the site). Dropping one observation is recoverable
 * by construction — the basis is compared against the STORED basis rather than the previous scan, so the
 * next completed scan still sees the same category as newly in use.
 */
@Component
class ScanConsentBasisListener(
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val consentBasisService: ConsentBasisService,
) {
    private val log = LoggerFactory.getLogger(ScanConsentBasisListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onScanCompleted(event: ScanCompleted) {
        runCatching { recordBasis(event) }
            .onFailure { error -> log.warn("Could not record consent basis for scan {}", event.scanId, error) }
    }

    private fun recordBasis(event: ScanCompleted) {
        // Re-read rather than carry findings on the event: the event is ids-only by design, and the
        // rows it points at are the authoritative ones (see [ScanCompleted]).
        val scan = scanRepository.findById(event.scanId).orElse(null) ?: return
        val cookies = scanCookieRepository.findByScanId(event.scanId)
        val findings = ScanFindings.of(cookies, scan.marketingTrackerCount ?: 0)
        consentBasisService.record(event.siteId, findings.categoriesInUse)
    }
}
