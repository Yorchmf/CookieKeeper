package com.complyr.billing

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The account's current plan does not include on-demand re-scan (Pro and Business) — 403. Mirrors
 * [CsvExportNotEntitledException]: the dashboard maps the stable [code] to a localized upgrade prompt, and
 * the English fallback names no plan or account detail.
 *
 * Not a dead end for the customer: every plan still gets a scheduled re-scan at its own
 * [Entitlements.rescanFrequency] cadence, so this gates *immediacy*, not re-scanning as such.
 */
class OnDemandRescanNotEntitledException :
    ApiException(
        HttpStatus.FORBIDDEN,
        code = "ON_DEMAND_RESCAN_NOT_ENTITLED",
        message = "On-demand re-scan requires the Pro plan",
    )
