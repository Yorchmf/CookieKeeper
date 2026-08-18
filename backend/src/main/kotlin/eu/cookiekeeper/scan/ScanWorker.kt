package eu.cookiekeeper.scan

import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scanner runtime role (Spring profile `scanner`, no web server): drains both scan queues.
 *
 * On each tick it claims and runs due jobs until the queues are empty or a per-tick cap is hit
 * (so one worker can burn down a backlog without a single tick running unbounded). `fixedDelay`
 * spacing means the next tick only starts after this one returns, so ticks never overlap on a
 * single instance; across instances, `FOR UPDATE SKIP LOCKED` in the queues' `claimNext` keeps
 * workers off each other's jobs. Each job's crawl runs outside any transaction — a slow crawl
 * holds no DB locks, only its visibility lease.
 *
 * Paid before free (funnel decision #6): each slot drains the paid [ScanQueue] first and only
 * falls through to the anonymous [PublicScanQueue] when the paid queue is empty, so a burst of
 * free-scan traffic can never starve a paying customer's scan. This keeps the reviewed paid path
 * byte-for-byte; the public path is a strictly lower-priority add-on.
 */
@Component
@Profile("scanner")
class ScanWorker(
    private val scanQueue: ScanQueue,
    private val crawler: ScanCrawler,
    private val publicScanQueue: PublicScanQueue,
    private val publicCrawler: PublicScanCrawler,
    private val properties: CookieKeeperProperties,
) {
    private val log = LoggerFactory.getLogger(ScanWorker::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        log.info("Scanner profile active — polling the scan queues")
    }

    @Scheduled(fixedDelayString = "\${complyr.scan.poll-interval-millis:5000}")
    fun poll() {
        var processed = 0
        while (processed < properties.scan.maxJobsPerPoll) {
            // Paid first: only reach for a free scan when there's no paid work waiting.
            scanQueue.claimNext()?.let { runScan(it) }
                ?: publicScanQueue.claimNext()?.let { runPublicScan(it) }
                ?: break
            processed++
        }
    }

    private fun runScan(claim: ClaimedScan) {
        try {
            val result = crawler.crawl(claim)
            scanQueue.markSucceeded(claim, result.pagesCrawled, result.marketingTrackerCount)
            log.info("Scan {} done: {} page(s)", claim.scanId, result.pagesCrawled)
        } catch (ex: ScanTargetException) {
            // The crawler classified this failure into a customer-safe reason code (unverified domain,
            // blocked/unresolvable target, timeout, unreachable). Only the code reaches the scan row;
            // the exception message (which may name the host/IP) stays in this server-side log.
            recordFailure(claim, ex.reason, ex)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // A worker must survive any single job's failure and record it as scan state; a thrown
            // crawl error becomes a retry (or dead-letter past max attempts), never a dead worker. An
            // unclassified error is reported generically so no internal detail leaks to the dashboard.
            recordFailure(claim, ScanFailureReason.INTERNAL, ex)
        }
    }

    private fun recordFailure(
        claim: ClaimedScan,
        reason: ScanFailureReason,
        ex: Exception,
    ) {
        log.warn("Scan {} attempt {}/{} failed ({})", claim.scanId, claim.attempt, claim.maxAttempts, reason.code, ex)
        scanQueue.markFailed(claim, reason.code)
    }

    private fun runPublicScan(claim: ClaimedPublicScan) {
        try {
            val result = publicCrawler.crawl(claim)
            publicScanQueue.markSucceeded(claim, result.marketingTrackerCount)
            log.info("Public scan {} done: {} page(s)", claim.publicScanId, result.pagesCrawled)
        } catch (ex: ScanTargetException) {
            // Same discipline as the paid path: only the customer-safe reason code reaches the scan
            // row; the exception message (which may name the visitor-supplied host/IP) stays server-side.
            recordPublicFailure(claim, ex.reason, ex)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            recordPublicFailure(claim, ScanFailureReason.INTERNAL, ex)
        }
    }

    private fun recordPublicFailure(
        claim: ClaimedPublicScan,
        reason: ScanFailureReason,
        ex: Exception,
    ) {
        log.warn(
            "Public scan {} attempt {}/{} failed ({})",
            claim.publicScanId,
            claim.attempt,
            claim.maxAttempts,
            reason.code,
            ex,
        )
        publicScanQueue.markFailed(claim, reason.code)
    }
}
