package eu.cookiekeeper.policy

import eu.cookiekeeper.scan.ScanCookieEntity
import eu.cookiekeeper.scan.ScanCookieRepository
import eu.cookiekeeper.scan.ScanRepository
import eu.cookiekeeper.scan.ScanStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Turns a site's latest completed scan plus the customer's business [PolicyDetails] into the immutable
 * [PolicyContext] the renderer consumes. Kept out of [PolicyService] so the cookie-sourcing concern —
 * "which cookies does this policy list, and how are they bucketed" — is separately owned and testable.
 */
@Component
class PolicyContextBuilder(
    private val scanRepository: ScanRepository,
    private val scanCookieRepository: ScanCookieRepository,
    private val clock: Clock,
) {
    fun build(
        siteId: UUID,
        details: PolicyDetails,
    ): PolicyContext {
        val cookies = cookies(siteId)
        return PolicyContext(
            companyName = details.companyName,
            contactEmail = details.contactEmail,
            websiteUrl = details.websiteUrl,
            address = details.address,
            // The date the document was generated, not the date it was scanned — a published policy is
            // versioned evidence of when *it* was written.
            updatedOn = LocalDate.now(clock),
            cookiesByCategory = cookies.byCategory,
            unclassified = cookies.unclassified,
        )
    }

    /**
     * The site's declared cookies as of its latest completed scan. Shared with the embeddable cookie
     * table, which needs the cookies without any of the business details a full [PolicyContext] carries.
     */
    fun cookies(siteId: UUID): PolicyCookies {
        val scan =
            scanRepository.findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE)
                ?: return PolicyCookies(byCategory = emptyMap(), unclassified = emptyList(), scannedOn = null)
        // Mirror ScanDetailResponse's split: a classified cookie always carries a category; anything
        // else (unknown, or the defensive known-but-uncategorized case) becomes an unclassified entry so
        // no detected cookie silently vanishes from a legal document.
        val (classified, unrecognized) = scanCookieRepository.findByScanId(scan.id).partition { it.isKnown && it.category != null }
        return PolicyCookies(
            byCategory =
                classified.groupBy(
                    { requireNotNull(it.category) },
                    { it.toPolicyCookie() },
                ),
            unclassified = unrecognized.map { it.toPolicyCookie() },
            scannedOn = LocalDate.ofInstant(scan.finishedAt ?: scan.createdAt, clock.zone),
        )
    }

    private fun ScanCookieEntity.toPolicyCookie(): PolicyCookie =
        PolicyCookie(name = name, provider = provider, expiry = expiry, domain = domain)
}
