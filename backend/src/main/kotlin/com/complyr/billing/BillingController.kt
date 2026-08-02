package com.complyr.billing

import com.complyr.billing.dto.CheckoutSessionRequest
import com.complyr.billing.dto.CheckoutSessionResponse
import com.complyr.billing.dto.EntitlementResponse
import com.complyr.billing.dto.PortalSessionResponse
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Authenticated billing endpoints. Not listed in [com.complyr.common.SecurityConfig]'s permitAll
 * set, so both require a valid access JWT (`anyRequest().authenticated()`). Each returns a
 * Stripe-hosted redirect URL in the standard envelope; the browser follows it. The Stripe webhook
 * (Slice 3) is the separate, unauthenticated, signature-verified endpoint.
 */
@RestController
@RequestMapping("/api/v1/billing")
class BillingController(
    private val billingService: BillingService,
    private val entitlementService: EntitlementService,
) {
    /** The current account's billing state + usage, for the dashboard billing page. */
    @GetMapping("/entitlement")
    fun entitlement(): ApiResponse<EntitlementResponse> =
        ApiResponse.success(EntitlementResponse.from(entitlementService.summarize(CurrentUser.id())))

    @PostMapping("/checkout-session")
    fun checkoutSession(
        @Valid @RequestBody request: CheckoutSessionRequest,
    ): ApiResponse<CheckoutSessionResponse> {
        // @Valid @NotNull guarantees a non-null plan reached here.
        val plan = requireNotNull(request.plan) { "plan must be validated non-null" }
        return ApiResponse.success(CheckoutSessionResponse(billingService.startCheckout(CurrentUser.id(), plan)))
    }

    @PostMapping("/portal-session")
    fun portalSession(): ApiResponse<PortalSessionResponse> =
        ApiResponse.success(PortalSessionResponse(billingService.openPortal(CurrentUser.id())))
}
