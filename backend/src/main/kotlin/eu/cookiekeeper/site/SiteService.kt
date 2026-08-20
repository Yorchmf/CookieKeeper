package eu.cookiekeeper.site

import eu.cookiekeeper.auth.EmailNotVerifiedException
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.banner.BannerConfigService
import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.common.violatedConstraint
import eu.cookiekeeper.site.dto.SiteDetailResponse
import eu.cookiekeeper.site.dto.SiteResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Site management. Every read/write is scoped by `(id, userId)` in a single query —
 * ownership enforcement and anti-enumeration in one: foreign ids look like true misses.
 */
@Service
class SiteService(
    private val siteRepository: SiteRepository,
    private val userRepository: UserRepository,
    private val entitlementService: EntitlementService,
    private val bannerConfigService: BannerConfigService,
    private val properties: CookieKeeperProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun list(
        userId: UUID,
        status: SiteStatus,
    ): List<SiteResponse> = siteRepository.findAllByUserIdAndStatus(userId, status).map(SiteResponse::from)

    @Transactional
    fun create(
        userId: UUID,
        rawDomain: String,
    ): SiteResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        // An Art. 17 tombstone must not be able to acquire new data with a still-valid access token
        // (ADR-20). Its verifiedAt is cleared too, so this is belt-and-braces — but "erased" is the
        // honest reason, and a future change to the verification rule must not silently open this path.
        if (user.isErased) throw UnauthenticatedException()
        if (user.verifiedAt == null) throw EmailNotVerifiedException()
        val domain = DomainValidator.normalize(rawDomain)
        ensureDomainAvailable(userId, domain)
        // Plan site-cap guard, after input + duplicate checks: a re-submitted existing domain still
        // gets the specific 409, while a genuinely new site is rejected (403) once the account is at
        // its plan's maxSites — which also freezes new sites for an Expired account (cap 0).
        entitlementService.requireCanAddSite(userId)
        val site =
            saveEnsuringDomainUniqueness(
                SiteEntity(
                    userId = userId,
                    domain = domain,
                    siteKey = generateSiteKey(),
                    createdAt = clock.instant(),
                    updatedAt = clock.instant(),
                ),
            )
        // Every site is renderable from creation: seed and publish its default v1 banner config
        // in the same transaction so the widget-config read never 404s for an active site.
        bannerConfigService.createDefaultFor(site.id)
        // Kick off the site's first cookie scan. The listener enqueues synchronously in THIS
        // transaction (see ScanEnqueueListener), so the scan commits or rolls back with the site.
        events.publishEvent(SiteCreatedEvent(site.id))
        return SiteResponse.from(site)
    }

    fun get(
        userId: UUID,
        siteId: UUID,
    ): SiteDetailResponse = detail(owned(userId, siteId))

    @Transactional
    fun update(
        userId: UUID,
        siteId: UUID,
        newDomain: String?,
    ): SiteDetailResponse {
        val site = owned(userId, siteId)
        val updated = newDomain?.let { changeDomain(site, it) } ?: site
        return detail(updated)
    }

    /**
     * Persist the site's "hide the Powered by CookieKeeper credit" preference. Ownership-scoped like every
     * other write; a no-op save is skipped so an idempotent re-toggle doesn't bump `updatedAt`. The
     * preference is stored as-is regardless of plan — the entitlement floor is applied only when the
     * effective branding is *resolved* (widget-config / hosted-policy reads), so a customer who sets it
     * while on the free tier keeps the choice and it activates the moment they upgrade.
     */
    @Transactional
    fun setBrandingPreference(
        userId: UUID,
        siteId: UUID,
        hideBranding: Boolean,
    ): SiteDetailResponse {
        val site = owned(userId, siteId)
        val updated =
            if (site.hideBranding == hideBranding) {
                site
            } else {
                siteRepository.save(site.copy(hideBranding = hideBranding, updatedAt = clock.instant()))
            }
        return detail(updated)
    }

    /** Soft archive only — sites are never hard-deleted. */
    @Transactional
    fun archive(
        userId: UUID,
        siteId: UUID,
    ) {
        val site = owned(userId, siteId)
        if (site.status == SiteStatus.ARCHIVED) return
        siteRepository.save(site.copy(status = SiteStatus.ARCHIVED, updatedAt = clock.instant()))
    }

    /**
     * Bring an archived site back to active — the inverse of [archive], and idempotent: restoring an
     * already-active site returns its detail without a write. Two create-time guards are re-run because a
     * restore *is* an acquisition of an active site:
     *  - the domain freed up while archived, so another active site may now hold it —
     *    [ensureDomainAvailable] (plus the unique-index race check in [saveEnsuringDomainUniqueness])
     *    surfaces the conflict as 409, exactly as create does;
     *  - an active site consumes a plan slot, so [EntitlementService.requireCanAddSite] runs (and, under
     *    its advisory lock, re-checks the erasure tombstone) — archive→restore must never become a way to
     *    exceed `maxSites` after a downgrade.
     * Verification state is preserved: archiving never disproved domain control, so a site verified before
     * archival stays verified.
     */
    @Transactional
    fun restore(
        userId: UUID,
        siteId: UUID,
    ): SiteDetailResponse {
        val site = owned(userId, siteId)
        if (site.status == SiteStatus.ACTIVE) return detail(site)
        ensureDomainAvailable(userId, site.domain)
        entitlementService.requireCanAddSite(userId)
        val restored = saveEnsuringDomainUniqueness(site.copy(status = SiteStatus.ACTIVE, updatedAt = clock.instant()))
        return detail(restored)
    }

    private fun changeDomain(
        site: SiteEntity,
        newDomain: String,
    ): SiteEntity {
        val domain = DomainValidator.normalize(newDomain)
        if (domain == site.domain) return site
        ensureDomainAvailable(site.userId, domain)
        // Ownership proof does not transfer between domains: verification restarts. Both columns must
        // be cleared together or `ck_sites_verification_method_pairs` (V15) rejects the row.
        return saveEnsuringDomainUniqueness(
            site.copy(
                domain = domain,
                verifiedAt = null,
                verificationMethod = null,
                updatedAt = clock.instant(),
            ),
        )
    }

    /**
     * Persists (with flush) so a concurrent write racing past [ensureDomainAvailable] is decided
     * by the `uq_sites_user_domain_active` partial unique index and surfaces as 409 — any other
     * integrity violation (e.g. a site-key collision) is rethrown and becomes a 500.
     */
    private fun saveEnsuringDomainUniqueness(site: SiteEntity): SiteEntity =
        try {
            siteRepository.saveAndFlush(site)
        } catch (ex: DataIntegrityViolationException) {
            if (ex.violatedConstraint() == UNIQUE_USER_DOMAIN_CONSTRAINT) throw DomainAlreadyRegisteredException()
            throw ex
        }

    private fun owned(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

    private fun ensureDomainAvailable(
        userId: UUID,
        domain: String,
    ) {
        if (siteRepository.existsByUserIdAndDomainAndStatus(userId, domain, SiteStatus.ACTIVE)) {
            throw DomainAlreadyRegisteredException()
        }
    }

    // The site page is authed and low-frequency, so the extra best-effort billing read for
    // `brandingRemovalEntitled` is fine here; it never throws (fails closed to "not entitled").
    private fun detail(site: SiteEntity): SiteDetailResponse =
        SiteDetailResponse.from(
            site,
            embedSnippet(site.siteKey),
            entitlementService.removeBrandingOrDefault(site.userId),
        )

    private fun embedSnippet(siteKey: String): String =
        """<script async src="${properties.cdnBaseUrl}/v1.js" data-complyr="$siteKey"></script>"""

    private fun generateSiteKey(): String =
        buildString(SITE_KEY_PREFIX.length + SITE_KEY_LENGTH) {
            append(SITE_KEY_PREFIX)
            repeat(SITE_KEY_LENGTH) { append(SITE_KEY_ALPHABET[random.nextInt(SITE_KEY_ALPHABET.length)]) }
        }

    companion object {
        private const val UNIQUE_USER_DOMAIN_CONSTRAINT = "uq_sites_user_domain_active"
        private const val SITE_KEY_PREFIX = "pk_"
        private const val SITE_KEY_LENGTH = 32
        private const val SITE_KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
