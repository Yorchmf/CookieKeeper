package eu.cookiekeeper.banner

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * Unknown site key, or a site with no published banner config yet, on the public widget-config
 * read. Both collapse to a 404 with the same code — site keys are public, so there is no
 * enumeration risk, and the widget treats either as "no config to render".
 */
class WidgetConfigNotFoundException :
    ApiException(HttpStatus.NOT_FOUND, code = "SITE_NOT_FOUND", message = "No widget configuration for this site key")
