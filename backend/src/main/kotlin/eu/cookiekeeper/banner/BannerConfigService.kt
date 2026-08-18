package eu.cookiekeeper.banner

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

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ): SiteEntity = siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()

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
        )
    }
}
