package eu.cookiekeeper.billing

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The account's current plan does not include consent-log CSV export (Business-only) — 403. The dashboard
 * maps the stable [code] to a localized upgrade prompt; the English fallback names no plan or account detail.
 */
class CsvExportNotEntitledException :
    ApiException(HttpStatus.FORBIDDEN, code = "CSV_EXPORT_NOT_ENTITLED", message = "CSV export requires the Business plan")
