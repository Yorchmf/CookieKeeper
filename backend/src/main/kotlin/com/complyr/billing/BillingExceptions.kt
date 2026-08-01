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

/**
 * Raised when an inbound webhook fails Stripe signature verification (missing/invalid `Stripe-
 * Signature`, or a body that doesn't match the signing secret). Returned as 400 so Stripe does NOT
 * retry a forged/misdirected request; a genuine transient issue would present as a 5xx elsewhere. The
 * message is generic and the raw reason is never logged — an attacker learns nothing from the reply.
 */
class WebhookSignatureException :
    ApiException(
        HttpStatus.BAD_REQUEST,
        code = "INVALID_SIGNATURE",
        message = "Invalid webhook signature",
    )

/**
 * Raised when a webhook body exceeds the accepted size cap before any parsing/verification. Bounds
 * the work an unauthenticated caller can force on the endpoint (the path is signature-gated, not rate-
 * limited): a real Stripe event is a few KB, so the cap only ever rejects abuse. 413 is terminal for
 * Stripe (it won't usefully retry a too-large body).
 */
class PayloadTooLargeException :
    ApiException(
        HttpStatus.PAYLOAD_TOO_LARGE,
        code = "PAYLOAD_TOO_LARGE",
        message = "Webhook payload too large",
    )
