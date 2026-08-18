package eu.cookiekeeper.billing

import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Unit tests for [BillingService]. Stripe I/O is faked via [FakeStripeGateway], which captures the
 * request the service built so we can assert plan→price mapping, customer reuse, and the absolute
 * redirect URLs — without touching Stripe or the SDK.
 */
class BillingServiceTest {
    private class FakeStripeGateway : StripeGateway {
        var lastCheckout: CheckoutRequest? = null
        var lastPortalCustomerId: String? = null
        var lastPortalReturnUrl: String? = null

        override fun createCheckoutSession(request: CheckoutRequest): String {
            lastCheckout = request
            return CHECKOUT_URL
        }

        override fun createPortalSession(
            customerId: String,
            returnUrl: String,
        ): String {
            lastPortalCustomerId = customerId
            lastPortalReturnUrl = returnUrl
            return PORTAL_URL
        }

        override fun cancelSubscription(subscriptionId: String) = throw NotImplementedError("no cancels here")

        override fun parseWebhookEvent(
            payload: String,
            signatureHeader: String,
        ): StripeWebhookEvent = throw NotImplementedError("BillingService does not parse webhooks")
    }

    private val gateway = FakeStripeGateway()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val userRepository = mockk<UserRepository>()

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "test-only-jwt-secret-0123456789-abcdefghij",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            billing =
                CookieKeeperProperties.Billing(
                    stripeSecretKey = "sk_test_dummy",
                    priceIds =
                        CookieKeeperProperties.Billing.PriceIds(
                            starter = "price_starter",
                            pro = "price_pro",
                            business = "price_business",
                        ),
                    automaticTax = false,
                ),
            appBaseUrl = "https://app.cookiekeeper.test",
            cdnBaseUrl = "https://cdn.cookiekeeper.test",
            mailFrom = "support@cookiekeeper.test",
        )

    private val planCatalog = PlanCatalog(properties)
    private val service = BillingService(gateway, subscriptionRepository, userRepository, planCatalog, properties)

    private val userId = UUID.randomUUID()
    private val user = UserEntity(id = userId, email = "owner@example.com", passwordHash = "hash")

    private fun subscription(
        stripeCustomerId: String?,
        status: String = "active",
    ): SubscriptionEntity =
        SubscriptionEntity(
            userId = userId,
            stripeCustomerId = stripeCustomerId,
            stripeSubId = "sub_x",
            plan = Plan.STARTER,
            status = status,
            periodEnd = Instant.parse("2026-09-01T00:00:00Z"),
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    @Test
    fun `startCheckout with no subscription creates a new customer from the email with the plan price`() {
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { subscriptionRepository.findByUserId(userId) } returns null

        val url = service.startCheckout(userId, Plan.PRO)

        assertEquals(CHECKOUT_URL, url)
        val request = requireNotNull(gateway.lastCheckout)
        assertEquals("price_pro", request.priceId)
        // The user id is stamped on the request so the gateway can attach it to the subscription
        // metadata — the link the webhook handler later recovers the account from.
        assertEquals(userId, request.userId)
        assertEquals(CheckoutCustomer.New("owner@example.com"), request.customer)
        assertEquals("https://app.cookiekeeper.test/billing?checkout=success", request.successUrl)
        assertEquals("https://app.cookiekeeper.test/billing?checkout=cancel", request.cancelUrl)
        assertEquals(false, request.automaticTax)
    }

    @Test
    fun `startCheckout reuses an existing stripe customer without loading the user`() {
        // A LAPSED subscription (canceled) may re-subscribe: reuse its Stripe customer, don't block it.
        every { subscriptionRepository.findByUserId(userId) } returns
            subscription(stripeCustomerId = "cus_existing", status = "canceled")

        service.startCheckout(userId, Plan.STARTER)

        val request = requireNotNull(gateway.lastCheckout)
        assertEquals(CheckoutCustomer.Existing("cus_existing"), request.customer)
        assertEquals("price_starter", request.priceId)
        // The returning-customer path must not incur a user lookup (email only needed for a new one).
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `startCheckout rejects a user who already has an active subscription`() {
        // Guard against double-Checkout: an active subscriber must be routed to the Portal, never mint
        // a second Stripe subscription (which the one-row-per-user constraint would then hide).
        every { subscriptionRepository.findByUserId(userId) } returns
            subscription(stripeCustomerId = "cus_active", status = "active")

        assertThrows<AlreadySubscribedException> { service.startCheckout(userId, Plan.PRO) }

        assertEquals(null, gateway.lastCheckout, "an active subscriber must never reach Stripe Checkout")
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `startCheckout allows re-subscribing after a trialing subscription lapses`() {
        // `trialing` counts as active (entitled), so it is also guarded — only genuinely-lapsed rows
        // (past_due here) may re-checkout. Confirms the guard keys on isActive, not a hardcoded status.
        every { subscriptionRepository.findByUserId(userId) } returns
            subscription(stripeCustomerId = "cus_lapsed", status = "past_due")

        service.startCheckout(userId, Plan.PRO)

        assertEquals(CheckoutCustomer.Existing("cus_lapsed"), requireNotNull(gateway.lastCheckout).customer)
    }

    @Test
    fun `startCheckout maps the business plan to its configured price id`() {
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { subscriptionRepository.findByUserId(userId) } returns null

        service.startCheckout(userId, Plan.BUSINESS)

        assertEquals("price_business", requireNotNull(gateway.lastCheckout).priceId)
    }

    @Test
    fun `startCheckout rejects an unknown user`() {
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { userRepository.findById(userId) } returns Optional.empty()

        assertThrows<UnauthenticatedException> { service.startCheckout(userId, Plan.PRO) }
    }

    @Test
    fun `openPortal returns a portal url with the account's customer and return path`() {
        every { subscriptionRepository.findByUserId(userId) } returns subscription(stripeCustomerId = "cus_portal")

        val url = service.openPortal(userId)

        assertEquals(PORTAL_URL, url)
        assertEquals("cus_portal", gateway.lastPortalCustomerId)
        assertEquals("https://app.cookiekeeper.test/billing", gateway.lastPortalReturnUrl)
    }

    @Test
    fun `openPortal fails when the user has never subscribed`() {
        every { subscriptionRepository.findByUserId(userId) } returns null

        assertThrows<NoBillingAccountException> { service.openPortal(userId) }
    }

    @Test
    fun `openPortal fails when the subscription has no stripe customer yet`() {
        every { subscriptionRepository.findByUserId(userId) } returns subscription(stripeCustomerId = null)

        assertThrows<NoBillingAccountException> { service.openPortal(userId) }
    }

    private companion object {
        const val CHECKOUT_URL = "https://checkout.stripe.test/session"
        const val PORTAL_URL = "https://portal.stripe.test/session"
    }
}
