package com.complyr.billing

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * Raised when a portal session is requested for an account that has no Stripe customer yet — i.e.
 * the user has never completed Checkout. The dashboard should route these users to the upgrade CTA
 * (which starts Checkout) instead of the Customer Portal.
 */
class NoBillingAccountException :
    ApiException(
        HttpStatus.CONFLICT,
        code = "NO_BILLING_ACCOUNT",
        message = "No billing account yet — start a subscription first",
    )

/**
 * Raised when a call to Stripe fails (network, API error, or a missing redirect URL). The message is
 * deliberately generic — Stripe error detail (which can carry the customer id) is logged server-side
 * by request id only, never surfaced to the client or written into logs verbatim (CLAUDE.md #4).
 */
class BillingUnavailableException :
    ApiException(
        HttpStatus.BAD_GATEWAY,
        code = "BILLING_UNAVAILABLE",
        message = "Billing is temporarily unavailable, please try again",
    )
