package com.complyr.banner.dto

import com.complyr.banner.BannerConfigDocument
import com.complyr.banner.BannerConfigEntity

/**
 * Public widget-config payload. Carries the site key (echoed for the widget's own bookkeeping),
 * the banner version the visitor is being shown (recorded later on the consent event), and the
 * full config document the widget renders from.
 */
data class WidgetConfigResponse(
    val siteKey: String,
    val bannerVersion: Int,
    val config: BannerConfigDocument,
) {
    companion object {
        fun from(
            siteKey: String,
            entity: BannerConfigEntity,
        ): WidgetConfigResponse =
            WidgetConfigResponse(
                siteKey = siteKey,
                bannerVersion = entity.version,
                config = entity.config,
            )
    }
}
