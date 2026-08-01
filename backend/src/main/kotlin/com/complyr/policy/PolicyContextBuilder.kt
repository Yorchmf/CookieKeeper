package com.complyr.policy

import com.complyr.scan.ScanCookieEntity
import com.complyr.scan.ScanCookieRepository
import com.complyr.scan.ScanRepository
import com.complyr.scan.ScanStatus
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
        val cookies =
            scanRepository
                .findFirstBySiteIdAndStatusOrderByCreatedAtDesc(siteId, ScanStatus.DONE)
                ?.let { scan -> scanCookieRepository.findByScanId(scan.id) }
                .orEmpty()
        // Mirror ScanDetailResponse's split: a classified cookie always carries a category; anything
        // else (unknown, or the defensive known-but-uncategorized case) becomes an unclassified entry so
        // no detected cookie silently vanishes from a legal document.
        val (classified, unrecognized) = cookies.partition { it.isKnown && it.category != null }
        return PolicyContext(
            companyName = details.companyName,
            contactEmail = details.contactEmail,
            websiteUrl = details.websiteUrl,
            address = details.address,
            updatedOn = LocalDate.now(clock),
            cookiesByCategory =
                classified.groupBy(
                    { requireNotNull(it.category) },
                    { it.toPolicyCookie() },
                ),
            unclassified = unrecognized.map { it.toPolicyCookie() },
        )
    }

    private fun ScanCookieEntity.toPolicyCookie(): PolicyCookie =
        PolicyCookie(name = name, provider = provider, expiry = expiry, domain = domain)
}
