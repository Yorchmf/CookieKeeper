package com.complyr.analytics

import com.complyr.analytics.dto.AccountAnalyticsResponse
import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.billing.EntitlementService
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cross-site ("All Sites") consent analytics — the account-level roll-up over every ACTIVE site the
 * authenticated customer owns. The ownership scope is the account itself, taken from the JWT principal
 * ([CurrentUser.id]); there is deliberately no site (or user) id in the path, so nothing to smuggle in.
 *
 * Entitlement (403) is checked before the roll-up is built, so a denial produces the normal JSON error
 * envelope rather than a partially-computed body — the Pro/Business gate mirrors the single-site CSV
 * export's guard-first ordering ([AnalyticsController]). Every figure is aggregated from our own data
 * (`consent_events`), never third-party telemetry; the optional `from`/`to` query params bound the
 * window, defaulting to the trailing 30 days.
 */
@RestController
@RequestMapping("/api/v1/analytics/accounts/rollup")
class AccountAnalyticsController(
    private val accountAnalyticsService: AccountAnalyticsService,
    private val entitlementService: EntitlementService,
) {
    @GetMapping
    fun rollup(filter: AnalyticsFilter): ApiResponse<AccountAnalyticsResponse> {
        val userId = CurrentUser.id()
        entitlementService.requireCrossSiteAnalytics(userId)
        return ApiResponse.success(accountAnalyticsService.rollup(userId, filter))
    }
}
