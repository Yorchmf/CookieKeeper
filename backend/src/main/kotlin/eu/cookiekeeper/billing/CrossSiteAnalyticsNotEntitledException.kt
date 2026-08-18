package eu.cookiekeeper.billing

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The account's current plan does not include the cross-site ("All Sites") analytics roll-up (Pro and
 * Business; Trial and Starter are single-site) — 403. The dashboard maps the stable [code] to a localized
 * upgrade prompt; the English fallback names no plan or account detail.
 */
class CrossSiteAnalyticsNotEntitledException :
    ApiException(
        HttpStatus.FORBIDDEN,
        code = "CROSS_SITE_ANALYTICS_NOT_ENTITLED",
        message = "Cross-site analytics requires the Pro or Business plan",
    )
