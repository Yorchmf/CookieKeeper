package com.complyr.scan

import com.complyr.auth.OpaqueTokens
import com.complyr.common.ApiException
import com.complyr.common.ComplyrProperties
import com.complyr.common.IpHasher
import com.complyr.scan.dto.PublicScanCreatedResponse
import com.complyr.scan.dto.PublicScanRequest
import com.complyr.site.DomainValidator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/** One requester already has the most in-flight anonymous scans we allow — retry once one finishes. */
class PublicScanCapacityException :
    ApiException(
        HttpStatus.TOO_MANY_REQUESTS,
        code = "SCAN_CAPACITY_EXCEEDED",
        message = "Too many scans in progress from this network. Please wait for one to finish and retry.",
    )

/**
 * Web-tier entry point for the anonymous free-scan funnel (docs ADR-12): normalize the visitor-supplied
 * domain, serve a fresh cached verdict when one exists, otherwise enqueue a new scan. It never crawls —
 * that is the scanner worker's job ([PublicScanCrawler]); this only validates input and manages the
 * queue/cache, so it runs in the web tier with no `scanner` profile dependency.
 *
 * Abuse posture (slice D): three layers guard crawl compute. The per-IP [com.complyr.common.RateLimitFilter]
 * `PUBLIC_SCAN` tier brakes request *rate* upstream of this service; a **honeypot** field short-circuits
 * naive bots to a no-op here; and a per-requester **concurrency cap** ([ComplyrProperties.Scan.maxConcurrentScansPerIp])
 * bounds how many crawls one requester (by rotating-salt `ip_hash`) can have in flight. The raw IP is
 * never stored or logged — only its [IpHasher] hash, and only on the persisted row for abuse analysis.
 *
 * The 24h per-domain cache is scoped to [ScanStatus.DONE] (see the repository finder): only a
 * *completed* result is ever reused, so a recent failed/running row neither shows as the verdict nor
 * pins the domain against a retry. On a hit it reuses the crawl *artifact*, not the *identity*: each
 * request gets its OWN row+token+`email` slot via [PublicScanQueue.reuseCachedResult] (findings copied,
 * no re-crawl), so a popular domain captures every visitor's lead independently and no visitor can read
 * another's email. A cache hit does not enqueue a crawl, so it is exempt from the concurrency cap.
 */
@Service
class PublicScanService(
    private val publicScanRepository: PublicScanRepository,
    private val queue: PublicScanQueue,
    private val ipHasher: IpHasher,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PublicScanService::class.java)

    /**
     * Request a scan for [request]'s domain. [clientIp] is the request-scoped source IP (never from the
     * body) — hashed for abuse analysis and the concurrency cap, never stored raw. Returns the read
     * token + status of either a still-fresh cached result (`done`) or a newly-queued scan (`queued`).
     * Throws [com.complyr.site.InvalidDomainException] (400) for a non-public domain and
     * [PublicScanCapacityException] (429) when this requester already has too many scans in flight.
     */
    fun request(
        request: PublicScanRequest,
        clientIp: String?,
    ): PublicScanCreatedResponse {
        // Honeypot first: a bot that filled the decoy field gets a plausible queued response with a
        // throwaway token (nothing persisted, no crawl), so it learns nothing about the trap. Checked
        // before domain validation so a garbage domain can't 400 and hint the request was inspected.
        if (!request.website.isNullOrBlank()) {
            log.debug("Rejected public scan: honeypot field populated")
            return PublicScanCreatedResponse(token = OpaqueTokens.generate(), status = ScanStatus.QUEUED.dbValue)
        }

        val domain = DomainValidator.normalize(request.domain)
        val ipHash = ipHasher.hash(clientIp)

        val cached =
            publicScanRepository.findFirstByDomainAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                domain = domain,
                status = ScanStatus.DONE,
                createdAtFrom = clock.instant().minus(CACHE_WINDOW),
            )
        if (cached != null) {
            // Reuse the crawl, not the identity: mint a fresh per-visitor row+token backed by the cached
            // findings so this visitor's future email lead can't collide with or leak another's.
            val token = queue.reuseCachedResult(cached = cached, ipHash = ipHash)
            return PublicScanCreatedResponse(token = token, status = ScanStatus.DONE.dbValue)
        }

        enforceConcurrencyCap(ipHash)
        val token = queue.enqueue(domain = domain, ipHash = ipHash)
        return PublicScanCreatedResponse(token = token, status = ScanStatus.QUEUED.dbValue)
    }

    /**
     * Reject the enqueue when this requester already has [ComplyrProperties.Scan.maxConcurrentScansPerIp]
     * scans queued or running. Skipped when no [ipHash] is available (blank source IP) — the request can't
     * be attributed, and the rate-limit tier plus edge controls remain in force. This is a soft cap: two
     * simultaneous requests can both read under the limit and both enqueue, so the true bound is
     * cap + in-flight concurrency — acceptable for a crawl-compute brake, not a hard invariant.
     */
    private fun enforceConcurrencyCap(ipHash: String?) {
        if (ipHash == null) return
        val inFlight = publicScanRepository.countByIpHashAndStatusIn(ipHash, IN_FLIGHT_STATUSES)
        if (inFlight >= properties.scan.maxConcurrentScansPerIp) {
            log.debug("Rejected public scan: per-requester concurrency cap reached ({} in flight)", inFlight)
            throw PublicScanCapacityException()
        }
    }

    companion object {
        /** Per-domain crawl-reuse window — a completed scan newer than this is served instead of re-crawling. */
        private val CACHE_WINDOW: Duration = Duration.ofHours(24)

        /** Statuses that count against the per-requester concurrency cap — a scan not yet terminal. */
        private val IN_FLIGHT_STATUSES = listOf(ScanStatus.QUEUED, ScanStatus.RUNNING)
    }
}
