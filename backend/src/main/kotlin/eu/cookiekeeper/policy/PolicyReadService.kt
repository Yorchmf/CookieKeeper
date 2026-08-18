package eu.cookiekeeper.policy

import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.policy.dto.PublicPolicyResponse
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Read side of the rendered policy document, shared by two callers with different gates:
 *
 * - [read] — the public, unauthenticated hosted page `/p/{publicId}`, addressed only by the stable
 *   opaque public id and **gated on domain verification** (ADR-17).
 * - [readBySite] — the ungated primitive, used by the hosted read above and by the dashboard's
 *   authenticated preview ([PolicyService.preview]), which has already proved ownership.
 *
 * Publishing a Complyr-hosted page for a domain is a public claim about that domain, so an unverified
 * customer must not be able to make it — otherwise anyone could register `victim.com`, generate a
 * policy, and point people at a plausible-looking cookie policy we serve for a domain they do not
 * control. Verification gates *publication*; it does not gate the owner's own preview, or the customer
 * could never see what they are about to publish.
 *
 * Every refusal on the public path is the same [PolicyNotFoundException] (404): unknown id, nothing
 * published, archived site and unverified site are byte-identical to each other. Anything else would
 * turn the public id into an oracle for "this site exists but hasn't verified yet". The id is a
 * 122-bit random UUID, so enumeration is infeasible regardless — the parity is defence in depth.
 */
@Service
class PolicyReadService(
    private val policyRepository: PolicyRepository,
    private val policySettingsRepository: PolicySettingsRepository,
    private val siteRepository: SiteRepository,
    private val entitlementService: EntitlementService,
) {
    /**
     * The public hosted read. Resolves the public id, refuses unless the owning site is active and
     * verified, then serves the current published version exactly as [readBySite] would.
     */
    @Transactional(readOnly = true)
    fun read(
        publicId: UUID,
        requestedLanguage: String?,
    ): PublicPolicyResponse {
        val settings = policySettingsRepository.findByPublicId(publicId) ?: throw PolicyNotFoundException()
        val site = siteRepository.findById(settings.siteId).orElse(null)
        if (site == null || site.status != SiteStatus.ACTIVE || site.verifiedAt == null) throw PolicyNotFoundException()
        return readBySite(settings, requestedLanguage, site.userId, site.hideBranding)
    }

    /**
     * The current published version for a site whose [settings] the caller has already resolved — with
     * no verification or ownership gate of its own. Callers must apply their own: [read] checks the
     * site is active and verified; [PolicyService.preview] checks ownership. Throws
     * [PolicyNotFoundException] when the site has never published.
     *
     * [ownerId] is the site owner (already known to both callers), used only to resolve the branding
     * entitlement — this primitive never re-loads the site itself. [hideBranding] is the site's own
     * preference; the effective suppression is that AND the owner's entitlement (see
     * [EntitlementService.effectiveRemoveBranding]).
     */
    @Transactional(readOnly = true)
    fun readBySite(
        settings: PolicySettingsEntity,
        requestedLanguage: String?,
        ownerId: UUID,
        hideBranding: Boolean,
    ): PublicPolicyResponse {
        val latest =
            policyRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(settings.siteId)
                ?: throw PolicyNotFoundException()

        val versionRows = policyRepository.findBySiteIdAndVersion(settings.siteId, latest.version)
        // `latest` is always one of `versionRows` (same version), so this fallback is total — no third
        // not-found path is needed, which also keeps the read within detekt's ThrowsCount budget.
        val chosen = PolicyVersionSelector.choose(versionRows, requestedLanguage) ?: latest

        return PublicPolicyResponse(
            version = latest.version,
            language = chosen.language,
            availableLanguages = PolicyVersionSelector.availableLanguages(versionRows),
            companyName = settings.details.companyName,
            html = chosen.html,
            publishedAt = chosen.publishedAt,
            // The customer's per-site preference AND the owner's plan entitlement (never the raw
            // entitlement alone, or a free-tier site that never opted out would lose its credit).
            // Resolved from the owner (never a re-loaded site row) so the hosted read and the owner
            // preview never disagree and [readBySite] stays free of the verification gate. Best-effort:
            // falls back to showing the footer, so a billing-read blip never 500s a public visitor.
            removeBranding = entitlementService.effectiveRemoveBranding(ownerId, hideBranding),
        )
    }
}
