package com.complyr.scan

import com.complyr.scan.dto.PublicScanCreatedResponse
import com.complyr.scan.dto.PublicScanRequest
import com.complyr.site.DomainValidator
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * Web-tier entry point for the anonymous free-scan funnel (docs ADR-12): normalize the visitor-supplied
 * domain, serve a fresh cached verdict when one exists, otherwise enqueue a new scan. It never crawls —
 * that is the scanner worker's job ([PublicScanCrawler]); this only validates input and manages the
 * queue/cache, so it runs in the web tier with no `scanner` profile dependency.
 *
 * The 24h per-domain cache is scoped to [ScanStatus.DONE] (see the repository finder): only a
 * *completed* result is ever reused, so a recent failed/running row neither shows as the verdict nor
 * pins the domain against a retry. Its cost/abuse bound is therefore ONLY realized *after* a first
 * crawl completes — it does NOT dedupe concurrent or repeated requests while a scan is still
 * queued/running, nor across distinct domains, and there is no per-requester rate limit yet. Until the
 * slice-D abuse controls (rate limit + honeypot + concurrency cap) land, the crawl/row/job count this
 * endpoint can drive is effectively unbounded; it must not be routed to the public internet before then.
 */
@Service
class PublicScanService(
    private val publicScanRepository: PublicScanRepository,
    private val queue: PublicScanQueue,
    private val clock: Clock,
) {
    /**
     * Request a scan for [request]'s domain. Returns the read token + status of either a still-fresh
     * cached result (`done`) or a newly-queued scan (`queued`). Throws
     * [com.complyr.site.InvalidDomainException] (400) when the domain is not a public DNS name.
     */
    fun request(request: PublicScanRequest): PublicScanCreatedResponse {
        val domain = DomainValidator.normalize(request.domain)
        val cached =
            publicScanRepository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                domain = domain,
                status = ScanStatus.DONE,
                createdAtFrom = clock.instant().minus(CACHE_WINDOW),
            )
        if (cached != null) {
            return PublicScanCreatedResponse(token = cached.publicToken, status = cached.status.dbValue)
        }
        // ipHash (rotating-salt requester hash) is wired in slice D alongside rate-limiting/honeypot.
        val token = queue.enqueue(domain = domain, ipHash = null)
        return PublicScanCreatedResponse(token = token, status = ScanStatus.QUEUED.dbValue)
    }

    companion object {
        /** Per-domain crawl-reuse window — a completed scan newer than this is served instead of re-crawling. */
        private val CACHE_WINDOW: Duration = Duration.ofHours(24)
    }
}
