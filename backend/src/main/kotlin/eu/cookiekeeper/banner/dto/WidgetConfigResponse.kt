package eu.cookiekeeper.banner.dto

import eu.cookiekeeper.banner.BannerConfigDocument
import eu.cookiekeeper.banner.BannerConfigEntity
import eu.cookiekeeper.banner.BannerTextDefaults

/**
 * Public widget-config payload. Carries the site key (echoed for the widget's own bookkeeping),
 * the banner version the visitor is being shown (recorded later on the consent event), the full
 * config document the widget renders from, and whether the site's plan suppresses the "Powered by
 * Complyr" attribution ([removeBranding], a paid-plan entitlement resolved from the site owner).
 */
data class WidgetConfigResponse(
    val siteKey: String,
    val bannerVersion: Int,
    val config: BannerConfigDocument,
    val removeBranding: Boolean,
) {
    companion object {
        fun from(
            siteKey: String,
            entity: BannerConfigEntity,
            removeBranding: Boolean,
        ): WidgetConfigResponse =
            WidgetConfigResponse(
                siteKey = siteKey,
                bannerVersion = entity.version,
                // Every visitor-facing read goes through here, so a config predating ADR-19 Slice 2
                // still reaches the widget with localized preferences-panel copy.
                config = BannerTextDefaults.complete(entity.config),
                removeBranding = removeBranding,
            )
    }
}
