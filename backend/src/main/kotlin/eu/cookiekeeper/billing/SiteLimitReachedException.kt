package eu.cookiekeeper.billing

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The account already holds the maximum number of active sites its current plan allows — 403. The
 * dashboard maps the stable [code] to a localized, upgrade-oriented message; the English text here is
 * only a fallback and carries no account-specific detail (no counts, no plan name — nothing to leak).
 */
class SiteLimitReachedException :
    ApiException(HttpStatus.FORBIDDEN, code = "SITE_LIMIT_REACHED", message = "Site limit reached for the current plan")
