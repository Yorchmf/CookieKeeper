package com.complyr.analytics

import com.complyr.analytics.dto.AccountOverviewResponse
import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The dashboard home in one request (JWT-authenticated; the scope is the caller's account, so there is no
 * path id to check). Sibling of [AnalyticsController], which is per-site — this is the roll-up above it.
 *
 * One endpoint rather than three: the home page needs headline figures AND the action list together, and
 * splitting them would put two loading states and two waterfalls on the first screen a customer sees. The
 * optional `from`/`to` params bound the consent figures; the service defaults to the trailing 30 days and
 * floors the window at the plan's retention (ADR-16).
 */
@RestController
@RequestMapping("/api/v1/overview")
class OverviewController(
    private val overviewService: OverviewService,
) {
    @GetMapping
    fun overview(filter: AnalyticsFilter): ApiResponse<AccountOverviewResponse> =
        ApiResponse.success(overviewService.overview(CurrentUser.id(), filter))
}
