package com.complyr.banner

import com.complyr.banner.dto.WidgetConfigResponse
import com.complyr.site.SiteRepository
import com.complyr.site.SiteStatus
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
     * Public widget-config read: resolves an active site by its public key and returns the
     * published config. Unknown key or unpublished site → [WidgetConfigNotFoundException] (404).
     */
    @Transactional(readOnly = true)
    fun widgetConfig(siteKey: String): WidgetConfigResponse {
        val site =
            siteRepository.findBySiteKeyAndStatus(siteKey, SiteStatus.ACTIVE)
                ?: throw WidgetConfigNotFoundException()
        val config = currentPublished(site.id) ?: throw WidgetConfigNotFoundException()
        return WidgetConfigResponse.from(site.siteKey, config)
    }
}
