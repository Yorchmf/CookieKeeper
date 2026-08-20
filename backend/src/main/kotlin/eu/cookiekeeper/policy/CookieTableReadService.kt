package eu.cookiekeeper.policy

import eu.cookiekeeper.banner.ConsentCategory
import eu.cookiekeeper.policy.dto.CookieTableLabels
import eu.cookiekeeper.policy.dto.CookieTableRow
import eu.cookiekeeper.policy.dto.CookieTableSection
import eu.cookiekeeper.policy.dto.PublicCookieTableResponse
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Read side of the embeddable cookie table (ADR-27): the current cookie list for a site, addressed by
 * its public site key, for the widget to paint into a `<div data-complyr-policy>` on the customer's own
 * policy page.
 *
 * Same cookies, same wording and same order as the hosted `/p/{publicId}` document — both go through
 * [PolicyContextBuilder.cookies] and [PolicyStrings] — so a customer whose lawyer approved *their* page
 * can keep that page and still have it follow the latest scan.
 *
 * Two gates deliberately differ from the hosted read ([PolicyReadService]):
 *
 * - **No domain verification.** ADR-17 gates the hosted page because *we* publish a claim about a
 *   domain we do not control. Here the customer's own server publishes it, and the list is derivable by
 *   anyone who can load their site — so the widget-config precedent (active site, public key) applies.
 * - **No published policy required.** A site that has never generated our document is exactly the site
 *   this feature exists for.
 *
 * An unknown key, an archived site and a suspended one all return the same 404
 * ([PolicyNotFoundException]) — the key is already public, but parity costs nothing.
 */
@Service
class CookieTableReadService(
    private val siteRepository: SiteRepository,
    private val policyContextBuilder: PolicyContextBuilder,
) {
    @Transactional(readOnly = true)
    fun read(
        siteKey: String,
        requestedLanguage: String?,
    ): PublicCookieTableResponse {
        val site =
            siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE)
                ?: throw PolicyNotFoundException()
        // An unsupported code falls back to the default rather than 400ing: this renders inside someone
        // else's page, where a stray `lang` attribute must never turn into a missing cookie table.
        val language = requestedLanguage?.let(PolicyLanguages::normalizeOrNull) ?: PolicyLanguages.DEFAULT
        val strings = PolicyStrings.forLanguage(language)
        val cookies = policyContextBuilder.cookies(site.id)
        return PublicCookieTableResponse(
            language = language,
            scannedOn = cookies.scannedOn?.toString(),
            labels =
                CookieTableLabels(
                    name = strings.colName,
                    provider = strings.colProvider,
                    expiry = strings.colExpiry,
                    updated = strings.updatedLabel,
                    noCookies = strings.noCookies,
                ),
            sections = sections(cookies, strings),
        )
    }

    /**
     * Canonical category order (necessary → preferences → statistics → marketing), skipping empty
     * categories, with the unclassified bucket last — identical to [PolicyRenderer.appendCookieSections]
     * so the embed and the generated document read the same way down the page.
     */
    private fun sections(
        cookies: PolicyCookies,
        strings: PolicyStrings,
    ): List<CookieTableSection> {
        val classified =
            ConsentCategory.entries.mapNotNull { category ->
                cookies.byCategory[category.key]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { section(strings.category(category.key), it, strings) }
            }
        val unclassified =
            cookies.unclassified
                .takeIf { it.isNotEmpty() }
                ?.let { section(strings.other, it, strings) }
        return classified + listOfNotNull(unclassified)
    }

    private fun section(
        heading: CategoryText,
        cookies: List<PolicyCookie>,
        strings: PolicyStrings,
    ): CookieTableSection =
        CookieTableSection(
            heading = heading.name,
            description = heading.description,
            cookies = cookies.map { it.toRow(strings) },
        )

    /** Same fallbacks as [PolicyRenderer.appendRow], resolved here so the widget carries no policy logic. */
    private fun PolicyCookie.toRow(strings: PolicyStrings): CookieTableRow =
        CookieTableRow(
            name = name,
            provider = provider?.takeIf { it.isNotBlank() } ?: domain?.takeIf { it.isNotBlank() } ?: strings.unknownProvider,
            expiry = expiry?.takeIf { it.isNotBlank() } ?: strings.sessionExpiry,
        )
}
