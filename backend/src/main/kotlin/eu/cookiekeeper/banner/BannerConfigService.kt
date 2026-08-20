package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.BannerConfigCopyResponse
import eu.cookiekeeper.banner.dto.BannerConfigResponse
import eu.cookiekeeper.banner.dto.BannerConfigUpdateRequest
import eu.cookiekeeper.banner.dto.WidgetConfigResponse
import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.site.SiteEntity
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import eu.cookiekeeper.site.SiteStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Banner configuration: seeds each new site's default published config, resolves the current
 * published config (used by consent validation), and serves the public widget-config read.
 */
@Service
class BannerConfigService(
    private val bannerConfigRepository: BannerConfigRepository,
    private val siteRepository: SiteRepository,
    private val entitlementService: EntitlementService,
    private val clock: Clock,
) {
    /**
     * Seeds and publishes the default v1 banner config for a freshly created site. Called
     * within the site-creation transaction so a site always has a renderable banner.
     */
    @Transactional
    fun createDefaultFor(siteId: UUID): BannerConfigEntity =
        bannerConfigRepository.save(
            BannerConfigEntity(
                siteId = siteId,
                version = DefaultBannerConfig.FIRST_VERSION,
                config = DefaultBannerConfig.document(),
                publishedAt = clock.instant(),
            ),
        )

    /** The config the widget currently serves for a site, or null if none is published. */
    fun currentPublished(siteId: UUID): BannerConfigEntity? =
        bannerConfigRepository.findFirstBySiteIdAndPublishedAtIsNotNullOrderByVersionDesc(siteId)

    /**
     * Authenticated read for the dashboard customizer: the owning user's current published config.
     * Scoped by `(siteId, userId)` so a foreign site id is a 404, indistinguishable from a real miss.
     */
    @Transactional(readOnly = true)
    fun getForOwner(
        userId: UUID,
        siteId: UUID,
    ): BannerConfigResponse {
        val site = requireOwnedSite(userId, siteId)
        val config = currentPublished(site.id) ?: throw BannerConfigNotFoundException()
        return BannerConfigResponse.from(config)
    }

    /**
     * Publishes a new banner version from the dashboard customizer. The request is validated and
     * normalized ([BannerConfigValidator]) before persistence — the document is served verbatim to
     * visitors. Appends a NEW version (configs are never overwritten); an advisory lock serializes
     * concurrent publishes for the same site so they can't collide on the version unique constraint.
     */
    @Transactional
    fun update(
        userId: UUID,
        siteId: UUID,
        request: BannerConfigUpdateRequest,
    ): BannerConfigResponse {
        val site = requireOwnedSite(userId, siteId)
        val document = BannerConfigValidator.validate(request)
        bannerConfigRepository.acquireSitePublishLock(advisoryLockKey(site.id))
        val saved =
            bannerConfigRepository.save(
                BannerConfigEntity(
                    siteId = site.id,
                    version = nextVersion(site.id),
                    config = document,
                    publishedAt = clock.instant(),
                ),
            )
        return BannerConfigResponse.from(saved)
    }

    /**
     * Applies one site's published banner to other sites the same account owns, as a new published
     * version on each target — the multi-site customer's "style it once" action.
     *
     * Deliberate properties:
     *  - **The document is read server-side**, never taken from the request, so this path cannot be used
     *    to publish a banner that skipped [BannerConfigValidator]. It is run through
     *    [BannerTextDefaults.complete] so copying a pre-ADR-19 source seeds complete targets, not stale ones.
     *  - **Site-agnostic by construction.** [BannerConfigDocument] holds only presentation (position, theme,
     *    categories, languages, texts). Everything site-specific — the site key, and notably `hideBranding`,
     *    which is a per-site *entitlement-gated* column and not part of the document — stays untouched, so a
     *    copy can never carry a paid branding removal onto a site that hasn't earned it.
     *  - **All-or-nothing.** One transaction: an unowned or archived target aborts the whole copy rather
     *    than leaving the account half-applied. Archived targets are refused because a site that isn't
     *    serving shouldn't silently accumulate versions.
     *  - **Append-only, like [update]** — targets get a *new* version; no existing config row is rewritten,
     *    so the customer can see exactly what changed and when.
     *  - Targets are locked **in id order** so two overlapping copies (or a copy racing a publish) take the
     *    per-site advisory locks in a consistent global order and cannot deadlock against each other.
     *
     * The source is silently dropped from the target set (copying a banner onto itself is a no-op, not an
     * error); a request that names *only* the source is [NoBannerConfigCopyTargetsException].
     */
    @Transactional
    fun copyToSites(
        userId: UUID,
        sourceSiteId: UUID,
        targetSiteIds: List<UUID>,
    ): BannerConfigCopyResponse {
        val source = requireOwnedSite(userId, sourceSiteId)
        val sourceConfig = currentPublished(source.id) ?: throw BannerConfigNotFoundException()
        val document = BannerTextDefaults.complete(sourceConfig.config)

        val targets = (targetSiteIds.toSet() - source.id).sorted()
        if (targets.isEmpty()) throw NoBannerConfigCopyTargetsException()

        val publishedAt = clock.instant()
        targets.forEach { targetId ->
            val target = requireActiveOwnedSite(userId, targetId)
            bannerConfigRepository.acquireSitePublishLock(advisoryLockKey(target.id))
            bannerConfigRepository.save(
                BannerConfigEntity(
                    siteId = target.id,
                    version = nextVersion(target.id),
                    config = document,
                    publishedAt = publishedAt,
                ),
            )
        }

        return BannerConfigCopyResponse(sourceVersion = sourceConfig.version, copiedToSiteIds = targets)
    }

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

    /**
     * Ownership alone isn't enough for a copy target: an archived site is not serving, so refuse rather
     * than quietly version a site the customer can't see in their list. Indistinguishable from "not
     * yours" on purpose — neither answer should let a caller probe which ids exist.
     */
    private fun requireActiveOwnedSite(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity =
        requireOwnedSite(userId, siteId).takeIf { it.status == SiteStatus.ACTIVE }
            ?: throw SiteNotFoundException()

    private fun nextVersion(siteId: UUID): Int = (bannerConfigRepository.findFirstBySiteIdOrderByVersionDesc(siteId)?.version ?: 0) + 1

    // Fold the 128-bit site id into the 64-bit key pg_advisory_xact_lock takes (mirrors PolicyService); a
    // rare collision only briefly serializes two unrelated sites' publishes, which is harmless.
    private fun advisoryLockKey(siteId: UUID): Long = siteId.mostSignificantBits xor siteId.leastSignificantBits

    /**
     * Public widget-config read: resolves an active site by its public key and returns the
     * published config. Unknown key or unpublished site → [WidgetConfigNotFoundException] (404).
     */
    @Transactional(readOnly = true)
    fun widgetConfig(siteKey: String): WidgetConfigResponse {
        val site =
            siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE)
                ?: throw WidgetConfigNotFoundException()
        val config = currentPublished(site.id) ?: throw WidgetConfigNotFoundException()
        return WidgetConfigResponse.from(
            site.siteKey,
            config,
            entitlementService.effectiveRemoveBranding(site.userId, site.hideBranding),
            site.consentBasisVersion,
        )
    }
}
