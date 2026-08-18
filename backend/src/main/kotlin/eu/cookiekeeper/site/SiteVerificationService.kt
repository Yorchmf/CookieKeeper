package eu.cookiekeeper.site

import eu.cookiekeeper.site.dto.SiteVerificationResponse
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Proves a customer controls the domain they registered (ADR-17), by looking for something only the
 * domain's operator could have placed there:
 *
 *  1. **The embed snippet** on `https://{domain}/`, matched structurally by [SnippetMatcher] — which
 *     doubles as the activation step, since finding it means the widget is genuinely live.
 *  2. **A `_cookiekeeper.{domain}` TXT record** carrying the site key ([DnsTxtLookup]) — the fallback for
 *     customers who will not put a script tag on their homepage first. This is ACME DNS-01's bar.
 *
 * Snippet first because it is the answer we *want* to be true: a customer whose snippet is found has
 * both proven ownership and finished installing, so the DNS path is only paid for when the cheap,
 * common case misses.
 *
 * **Verification gates publishing and crawl depth, not scanning.** An unverified site is still crawled
 * (in [eu.cookiekeeper.scan.CrawlMode.QUICK], the same posture the anonymous free-scan funnel already
 * applies to arbitrary unowned domains); what it cannot do is publish a Complyr-hosted cookie policy at
 * `/p/{publicId}` for a domain it may not own.
 *
 * **What the caller learns.** A miss is an HTTP 200 with `verified: false` and one of exactly two
 * reasons, because "your snippet isn't there yet" is a normal outcome the customer must read and act on
 * rather than an error to toast away. The reason set is deliberately coarse: an SSRF refusal, a DNS
 * failure, a timeout, a 500 and a wrong content type all collapse into `unreachable`. Distinguishing
 * them would turn this endpoint — which dials a host of the caller's choosing — into an internal-network
 * mapping oracle. [SiteVerificationFetcher] enforces the same contract one layer down.
 *
 * **No `@Transactional` on [verify].** It makes two network calls that can take the better part of a
 * minute in the worst case; holding a pooled DB connection across them would let a handful of hostile
 * domains starve the pool for every other tenant. The one write is a single `save`, transactional on
 * its own.
 */
@Service
class SiteVerificationService(
    private val siteRepository: SiteRepository,
    private val fetcher: SiteVerificationFetcher,
    private val dnsTxtLookup: DnsTxtLookup,
    private val cdnHost: CdnHost,
    private val clock: Clock,
) {
    /**
     * Attempt to verify [siteId], owned by [userId].
     *
     * Already-verified sites return immediately **without any outbound request**. That keeps the
     * operation idempotent, and it stops a verified site from being a repeatable, authenticated way to
     * make the `api` container dial an arbitrary host on demand.
     *
     * Suppression: `ReturnCount` — each return is a distinct terminal outcome (not owned, already
     * verified, missed), and folding them into one expression would hide which one fired.
     */
    @Suppress("ReturnCount")
    fun verify(
        userId: UUID,
        siteId: UUID,
    ): SiteVerificationResponse {
        // Ownership and existence in one query: another user's site is a 404, never a 403 (the
        // anti-enumeration contract the rest of SiteService keeps). An archived site is likewise a 404 —
        // it has no widget, no policy and nothing to activate.
        val site =
            siteRepository.findByIdAndUserId(siteId, userId)?.takeIf { it.status == SiteStatus.ACTIVE }
                ?: throw SiteNotFoundException()
        site.verifiedAt?.let { return SiteVerificationResponse.verified(it, site.verificationMethod) }

        val html = fetcher.fetchHomepage(site.domain)
        val method =
            when {
                html != null && SnippetMatcher.matches(html, site.siteKey, cdnHost.value) -> VerificationMethod.SNIPPET
                dnsTxtLookup.hasSiteKeyRecord(site.domain, site.siteKey) -> VerificationMethod.DNS_TXT
                // A homepage we could not fetch at all is a different piece of advice — "we couldn't
                // reach you" sends the customer to their DNS/firewall, not to their <head>.
                html == null -> return SiteVerificationResponse.failed(REASON_UNREACHABLE)
                else -> return SiteVerificationResponse.failed(REASON_SNIPPET_NOT_FOUND)
            }

        val now = clock.instant()
        siteRepository.save(site.copy(verifiedAt = now, verificationMethod = method, updatedAt = now))
        return SiteVerificationResponse.verified(now, method)
    }

    private companion object {
        /** We reached the homepage; the snippet was not on it (and no TXT record either). */
        const val REASON_SNIPPET_NOT_FOUND = "snippet_not_found"

        /** Everything else, collapsed on purpose — see the class KDoc on the oracle. */
        const val REASON_UNREACHABLE = "unreachable"
    }
}
