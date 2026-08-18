package eu.cookiekeeper.banner

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * A submitted banner configuration failed semantic validation (bad position, non-hex color, unknown
 * category, unsupported language, missing per-language text, …). The message is a static, client-safe
 * reason — it never echoes the offending value, so there is nothing to inject back to the caller.
 */
class InvalidBannerConfigException(
    message: String,
) : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_BANNER_CONFIG", message = message)

/**
 * An owned site has no published banner config on the authenticated read. Defensive only — every site
 * is seeded a v1 at creation — but it keeps a missing config a clean 404 rather than a 500.
 */
class BannerConfigNotFoundException :
    ApiException(HttpStatus.NOT_FOUND, code = "BANNER_CONFIG_NOT_FOUND", message = "No banner configuration for this site")
