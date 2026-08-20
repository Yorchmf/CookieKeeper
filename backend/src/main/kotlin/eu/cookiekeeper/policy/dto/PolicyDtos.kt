package eu.cookiekeeper.policy.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Authenticated request to (re)generate and publish a site's cookie policy. The business details fill
 * the template ([eu.cookiekeeper.policy.PolicyRenderer]); [languages] optionally narrows which of the five
 * supported languages to render (defaults to the site's banner languages). All values are validated at
 * the boundary here and HTML-escaped at render time — never trusted downstream.
 */
data class PolicyGenerationRequest(
    @field:NotBlank
    @field:Size(max = MAX_COMPANY_NAME)
    val companyName: String,
    @field:NotBlank
    @field:Email
    @field:Size(max = MAX_EMAIL)
    val contactEmail: String,
    // Optional: when blank the service defaults it to https://{site.domain}. Bounded to a sane URL length.
    @field:Size(max = MAX_URL)
    val websiteUrl: String? = null,
    @field:Size(max = MAX_ADDRESS)
    val address: String? = null,
    // Optional subset of supported languages; unsupported entries are dropped, empty/absent → defaults.
    // Bounded at the boundary so a request can't submit an oversized array to churn the normalize pass.
    @field:Size(max = MAX_LANGUAGES)
    val languages: List<String>? = null,
) {
    companion object {
        const val MAX_COMPANY_NAME = 200
        const val MAX_EMAIL = 254
        const val MAX_URL = 2048
        const val MAX_ADDRESS = 500
        const val MAX_LANGUAGES = 5
    }
}

/** Result of a generate call: the new version and where it is hosted. */
data class PolicyGenerationResponse(
    val version: Int,
    val publicId: String,
    val hostedUrl: String,
    val languages: List<String>,
)

/** The site's currently published policy, for the dashboard's policy view. */
data class PolicyCurrentResponse(
    val version: Int,
    val publicId: String,
    val hostedUrl: String,
    val languages: List<String>,
    val publishedAt: Instant?,
)

/**
 * Public hosted-page payload (no auth): the rendered [html] block for one [language], plus the
 * [availableLanguages] of this version for the page's language switcher and [companyName]/[version]
 * for the page title and footer. Addressed only by the opaque public id. [removeBranding] is the
 * site owner's plan entitlement (paid plans only), gating the "Powered by CookieKeeper" footer.
 */
data class PublicPolicyResponse(
    val version: Int,
    val language: String,
    val availableLanguages: List<String>,
    val companyName: String,
    val html: String,
    val publishedAt: Instant?,
    val removeBranding: Boolean,
)

/**
 * Payload for the embeddable cookie table (docs §4.5, ADR-27): everything the widget needs to paint
 * the current cookie list into a `<div data-complyr-policy>` on the customer's *own* policy page.
 *
 * Deliberately data, not HTML. The widget builds every node with `createElement`/`textContent` and has
 * no HTML sink anywhere; shipping markup for it to inject would put one on every visitor's page for no
 * gain, since the rendered result is identical either way.
 *
 * Every display fallback is already resolved server-side (provider → domain → "—", no expiry →
 * "Session"), the sections arrive in the canonical order the hosted policy uses, and all wording comes
 * from the same [eu.cookiekeeper.policy.PolicyStrings] bundle the generated document uses — so the
 * embed and the hosted page can never word or order the same cookies differently.
 *
 * [scannedOn] is the ISO date of the scan behind this list (null when the site has never completed
 * one, which is also the only case where [sections] is empty *and* the site is live).
 */
data class PublicCookieTableResponse(
    val language: String,
    val scannedOn: String?,
    val labels: CookieTableLabels,
    val sections: List<CookieTableSection>,
)

/** Column headers plus the two standalone strings the table needs, in the resolved language. */
data class CookieTableLabels(
    val name: String,
    val provider: String,
    val expiry: String,
    val updated: String,
    val noCookies: String,
)

/** One category section: its heading, its explanatory blurb, and its rows. Never empty. */
data class CookieTableSection(
    val heading: String,
    val description: String,
    val cookies: List<CookieTableRow>,
)

/** One row. All three values are display-ready strings — the widget adds no fallbacks of its own. */
data class CookieTableRow(
    val name: String,
    val provider: String,
    val expiry: String,
)
