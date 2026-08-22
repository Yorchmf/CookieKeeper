package eu.cookiekeeper.billing

import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.model.Subscription
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import eu.cookiekeeper.common.CookieKeeperProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import com.stripe.param.billingportal.SessionCreateParams as PortalSessionCreateParams

/**
 * [StripeGateway] backed by the real Stripe SDK ([StripeClient], the instance-based v33 API — not the
 * deprecated global `Stripe.apiKey` statics). Kept intentionally thin: it only maps our request
 * shapes onto the SDK builders and translates the SDK's checked [StripeException] into a generic
 * [BillingUnavailableException]. On failure it logs the Stripe request id / status / code only —
 * never the exception message or payload, which can carry the customer id or email (CLAUDE.md #4).
 *
 * [webhookSecret] is the endpoint signing secret; [parseWebhookEvent] verifies every inbound body
 * against it before this app trusts a single field of the event.
 */
@Service
class StripeApiGateway(
    private val stripeClient: StripeClient,
    private val properties: CookieKeeperProperties,
) : StripeGateway {
    private val log = LoggerFactory.getLogger(StripeApiGateway::class.java)

    init {
        // Fail fast on a blank signing secret. `application.yml` only fails when STRIPE_WEBHOOK_SECRET
        // is UNSET; exporting it EMPTY resolves to "" and `Webhook.constructEvent` would then HMAC with
        // an empty key — silently accepting forged webhooks. Validate here (the bean that consumes it),
        // mirroring how the ConsentOriginToken / Brevo beans reject a blank secret at startup, so the
        // empty test-only default on `Billing.webhookSecret` still lets the data class construct.
        require(properties.billing.webhookSecret.isNotBlank()) {
            "cookiekeeper.billing.webhook-secret (STRIPE_WEBHOOK_SECRET) must not be blank — an empty signing " +
                "secret makes inbound Stripe webhook signatures forgeable"
        }
    }

    private companion object {
        /** Event-type prefix for the `customer.subscription.*` family the handler acts on. */
        const val SUBSCRIPTION_EVENT_PREFIX = "customer.subscription."

        /** Stripe's error code for "this object does not exist" — see [cancelSubscription]. */
        const val STRIPE_CODE_RESOURCE_MISSING = "resource_missing"
        const val HTTP_NOT_FOUND = 404

        /**
         * Stripe subscription statuses from which there is no further billing and no valid cancel call.
         * [cancelSubscription] treats reaching one of these as success so a retried account erasure is
         * not blocked by its own earlier, partially-successful attempt.
         */
        val TERMINAL_SUBSCRIPTION_STATUSES = setOf("canceled", "incomplete_expired")
    }

    override fun createCheckoutSession(request: CheckoutRequest): String {
        val params =
            SessionCreateParams
                .builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .apply {
                    // Sealed CheckoutCustomer guarantees exactly one branch; Stripe rejects both set.
                    when (val customer = request.customer) {
                        is CheckoutCustomer.Existing -> setCustomer(customer.customerId)
                        is CheckoutCustomer.New -> setCustomerEmail(customer.email)
                    }
                }.setSuccessUrl(request.successUrl)
                .setCancelUrl(request.cancelUrl)
                // client_reference_id + subscription metadata both carry our user id: the former lets
                // `checkout.session.completed` self-identify, the latter stamps every later
                // `customer.subscription.*` event so the webhook handler links without a lookup.
                .setClientReferenceId(request.userId.toString())
                .setSubscriptionData(
                    SessionCreateParams.SubscriptionData
                        .builder()
                        .putMetadata(STRIPE_METADATA_USER_ID, request.userId.toString())
                        .build(),
                ).addLineItem(
                    SessionCreateParams.LineItem
                        .builder()
                        .setPrice(request.priceId)
                        .setQuantity(1L)
                        .build(),
                ).setAutomaticTax(
                    SessionCreateParams.AutomaticTax
                        .builder()
                        .setEnabled(request.automaticTax)
                        .build(),
                ).build()
        return try {
            stripeClient
                .v1()
                .checkout()
                .sessions()
                .create(params)
                .url ?: throw BillingUnavailableException()
        } catch (e: StripeException) {
            logStripeFailure("checkout session", e)
            throw BillingUnavailableException()
        }
    }

    override fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String {
        val params =
            PortalSessionCreateParams
                .builder()
                .setCustomer(customerId)
                .setReturnUrl(returnUrl)
                .build()
        return try {
            stripeClient
                .v1()
                .billingPortal()
                .sessions()
                .create(params)
                .url ?: throw BillingUnavailableException()
        } catch (e: StripeException) {
            logStripeFailure("portal session", e)
            throw BillingUnavailableException()
        }
    }

    /**
     * Cancels a subscription, idempotently — this must be safe to call more than once for the same id.
     *
     * The account erasure calls it before opening its transaction (ADR-20), so a failure anywhere in that
     * transaction leaves an account whose subscription is already cancelled at Stripe but whose rows are
     * all intact. The customer's only recourse is to press "delete my account" again, and Stripe rejects
     * cancelling an already-cancelled subscription — so a naive implementation converts one transient
     * database error into a permanently undeletable account, i.e. an Art. 17 request we can never fulfil.
     * Reading the current status first and cancelling only a live subscription makes the retry succeed.
     */
    override fun cancelSubscription(subscriptionId: String) {
        try {
            // "Stripe has never heard of this subscription" and "it is already cancelled" are both the
            // SUCCESS case: the caller needs the guarantee that the account is no longer billed, nothing
            // more. Everything else (network, auth, rate limit) must abort the erasure before a single row
            // is destroyed.
            val subscription = stripeClient.v1().subscriptions().retrieve(subscriptionId)
            if (subscription.status in TERMINAL_SUBSCRIPTION_STATUSES) {
                log.info("Stripe subscription already in a terminal state at cancel; treating as cancelled")
                return
            }
            stripeClient.v1().subscriptions().cancel(subscriptionId)
        } catch (e: StripeException) {
            if (e.statusCode == HTTP_NOT_FOUND || e.code == STRIPE_CODE_RESOURCE_MISSING) {
                log.info("Stripe subscription already absent at cancel; treating as cancelled")
                return
            }
            logStripeFailure("subscription cancel", e)
            throw BillingUnavailableException()
        }
    }

    // SwallowedException is intentional here: the Stripe exception can echo the attacker-supplied
    // signature/body, so we deliberately drop it and surface only a generic typed failure (no cause
    // chained, nothing from `e` logged) — a forger/misroute must learn nothing from the rejection.
    @Suppress("SwallowedException")
    override fun parseWebhookEvent(
        payload: String,
        signatureHeader: String,
    ): StripeWebhookEvent {
        val event =
            try {
                Webhook.constructEvent(payload, signatureHeader, properties.billing.webhookSecret)
            } catch (e: SignatureVerificationException) {
                log.warn("Rejected Stripe webhook: signature verification failed")
                throw WebhookSignatureException()
            }
        return StripeWebhookEvent(
            id = event.id,
            type = event.type,
            created = Instant.ofEpochSecond(event.created),
            payload = payload,
            data = reduce(event.type, extractSubscription(event)),
        )
    }

    /**
     * Pull the typed [Subscription] out of a `customer.subscription.*` event, or null for any other
     * type. Prefer the SDK's version-matched [java.util.Optional] object; fall back to
     * `deserializeUnsafe()` only when the account's API version differs from the SDK's (the object is
     * still the same shape for the fields we read). A deserialization failure yields null → Ignored,
     * so a malformed/unexpected event is logged for audit but never crashes the handler.
     */
    private fun extractSubscription(event: Event): Subscription? {
        if (!event.type.startsWith(SUBSCRIPTION_EVENT_PREFIX)) return null
        val deserializer = event.dataObjectDeserializer
        val obj =
            deserializer.getObject().orElseGet {
                // Narrow the swallow to the SDK's deserialization failure; rethrow JVM Errors
                // (OOM/StackOverflow) rather than masking them as a benign null → Ignored.
                runCatching { deserializer.deserializeUnsafe() }
                    .getOrElse { cause -> if (cause is Error) throw cause else null }
            }
        return obj as? Subscription
    }

    /** Map an extracted subscription (or its absence) to our closed [StripeEventData]. */
    private fun reduce(
        type: String,
        subscription: Subscription?,
    ): StripeEventData {
        if (subscription == null) return StripeEventData.Ignored
        val item = subscription.items?.data?.firstOrNull()
        return StripeEventData
            .SubscriptionChanged(
                userId = subscription.metadata?.get(STRIPE_METADATA_USER_ID)?.let(::parseUuidOrNull),
                subscriptionId = subscription.id,
                customerId = subscription.customer,
                status = subscription.status,
                priceId = item?.price?.id,
                currentPeriodEnd = item?.currentPeriodEnd?.let(Instant::ofEpochSecond),
                subscriptionCreatedAt = Instant.ofEpochSecond(subscription.created),
            ).also {
                // Note the type only; never log ids/metadata (customer-linkable). Aids audit of what was applied.
                log.info("Parsed Stripe subscription event type={}", type)
            }
    }

    private fun parseUuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun logStripeFailure(
        operation: String,
        e: StripeException,
    ) {
        // Request id + status + code are safe to log and are what Stripe support needs; the message
        // and body are NOT logged (they can echo the customer id/email).
        log.error(
            "Stripe {} create failed (status={}, code={}, requestId={})",
            operation,
            e.statusCode,
            e.code,
            e.requestId,
        )
    }
}
