package com.complyr.scan

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scanner runtime role (Spring profile `scanner`, no web server): drains the scan queue.
 *
 * On each tick it claims and runs due jobs until the queue is empty or a per-tick cap is hit
 * (so one worker can burn down a backlog without a single tick running unbounded). `fixedDelay`
 * spacing means the next tick only starts after this one returns, so ticks never overlap on a
 * single instance; across instances, `FOR UPDATE SKIP LOCKED` in [ScanQueue.claimNext] keeps
 * workers off each other's jobs. Each job's crawl runs outside any transaction — a slow crawl
 * holds no DB locks, only its visibility lease.
 */
@Component
@Profile("scanner")
class ScanWorker(
    private val scanQueue: ScanQueue,
    private val crawler: ScanCrawler,
    private val properties: ComplyrProperties,
) {
    private val log = LoggerFactory.getLogger(ScanWorker::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        log.info("Scanner profile active — polling the scan queue")
    }

    @Scheduled(fixedDelayString = "\${complyr.scan.poll-interval-millis:5000}")
    fun poll() {
        var processed = 0
        while (processed < properties.scan.maxJobsPerPoll) {
            val claim = scanQueue.claimNext() ?: break
            runScan(claim)
            processed++
        }
    }

    private fun runScan(claim: ClaimedScan) {
        try {
            val result = crawler.crawl(claim)
            scanQueue.markSucceeded(claim, result.pagesCrawled)
            log.info("Scan {} done: {} page(s)", claim.scanId, result.pagesCrawled)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // A worker must survive any single job's failure and record it as scan state; a thrown
            // crawl error becomes a retry (or dead-letter past max attempts), never a dead worker.
            // Only a safe reason CODE reaches the (customer-visible) scan row — the raw exception, which
            // may carry internal host/IP/stack detail, is confined to this server-side log line.
            // Slice 1's crawler is a no-op, so any failure here is an internal error; Slice 2 maps
            // concrete crawl error types to their own safe reasons.
            val reason = ScanFailureReason.INTERNAL
            log.warn("Scan {} attempt {}/{} failed ({})", claim.scanId, claim.attempt, claim.maxAttempts, reason.code, ex)
            scanQueue.markFailed(claim, reason.code)
        }
    }
}
