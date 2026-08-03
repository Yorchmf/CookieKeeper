package com.complyr.billing

import com.complyr.common.ComplyrProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Unit tests for [BillingWebhookService] — the inbox orchestration. Stripe parsing is faked (a bad
 * signature is simulated by throwing [WebhookSignatureException]); the repositories are mocked so we
 * assert the record→apply→stamp sequence, idempotency, the out-of-order watermark guard, and that a
 * signature failure records nothing. The transaction manager is a no-op so [org.springframework
 * .transaction.support.TransactionTemplate] runs each callback inline.
 */
class BillingWebhookServiceTest {
    /** A [PlatformTransactionManager] that runs the callback with no real transaction (test inline). */
    private class NoopTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }

    private class FakeGateway : StripeGateway {
        var event: StripeWebhookEvent? = null
        var signatureError: Boolean = false

        override fun createCheckoutSession(request: CheckoutRequest): String = error("unused")

        override fun createPortalSession(
            customerId: String,
            returnUrl: String,
        ): String = error("unused")

        override fun parseWebhookEvent(
            payload: String,
            signatureHeader: String,
        ): StripeWebhookEvent {
            if (signatureError) throw WebhookSignatureException()
            return requireNotNull(event) { "test must set the event" }
        }
    }

    private val gateway = FakeGateway()
    private val stripeEventRepository = mockk<StripeEventRepository>(relaxed = true)
    private val subscriptionRepository = mockk<SubscriptionRepository>()

    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "test-only-jwt-secret-0123456789-abcdefghij",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            billing =
                ComplyrProperties.Billing(
                    priceIds =
                        ComplyrProperties.Billing.PriceIds(
                            starter = "price_starter",
                            pro = "price_pro",
                            business = "price_business",
                        ),
                ),
            appBaseUrl = "https://app.complyr.test",
            cdnBaseUrl = "https://cdn.complyr.test",
            mailFrom = "no-reply@complyr.test",
        )

    private val now = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val service =
        BillingWebhookService(
            gateway = gateway,
            stripeEventRepository = stripeEventRepository,
            subscriptionRepository = subscriptionRepository,
            planCatalog = PlanCatalog(properties),
            clock = clock,
            eventPublisher = eventPublisher,
            transactionManager = NoopTransactionManager(),
        )

    private val userId = UUID.randomUUID()
    private val eventCreated = Instant.parse("2026-08-01T11:59:00Z")

    @BeforeEach
    fun stubSubscriptionLock() {
        // Non-relaxed mock: the per-subscription advisory lock taken at the top of applySubscription
        // must be stubbed for every path that reaches it.
        every { subscriptionRepository.acquireSubscriptionLock(any()) } returns 1L
    }

    private fun subscriptionEvent(
        userId: UUID? = this.userId,
        subscriptionId: String = "sub_1",
        customerId: String? = "cus_1",
        status: String = "active",
        priceId: String? = "price_pro",
        eventId: String = "evt_1",
    ) = StripeWebhookEvent(
        id = eventId,
        type = "customer.subscription.updated",
        created = eventCreated,
        payload = "{}",
        data =
            StripeEventData.SubscriptionChanged(
                userId = userId,
                subscriptionId = subscriptionId,
                customerId = customerId,
                status = status,
                priceId = priceId,
                currentPeriodEnd = eventCreated.plusSeconds(THIRTY_DAYS_SECONDS),
            ),
    )

    private fun recorded(processedAt: Instant?) =
        StripeEventEntity(
            stripeEventId = "evt_1",
            type = "customer.subscription.updated",
            payload = "{}",
            receivedAt = now,
            processedAt = processedAt,
        )

    private fun existingSubscription(
        stripeEventAt: Instant?,
        plan: Plan = Plan.STARTER,
        status: String = "active",
    ) = SubscriptionEntity(
        userId = userId,
        stripeCustomerId = "cus_1",
        stripeSubId = "sub_1",
        plan = plan,
        status = status,
        periodEnd = now,
        createdAt = now.minusSeconds(ONE_DAY_SECONDS),
        updatedAt = now.minusSeconds(ONE_DAY_SECONDS),
        stripeEventAt = stripeEventAt,
    )

    @Test
    fun `handle records the inbox then creates a new subscription from a first event`() {
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { subscriptionRepository.findByStripeCustomerId("cus_1") } returns null
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        verify { stripeEventRepository.insertIfAbsent(any(), "evt_1", any(), "{}", now) }
        assertEquals(userId, saved.captured.userId)
        assertEquals(Plan.PRO, saved.captured.plan, "price_pro maps to the PRO plan")
        assertEquals("active", saved.captured.status)
        assertEquals("sub_1", saved.captured.stripeSubId)
        assertEquals(eventCreated, saved.captured.stripeEventAt, "the watermark is set to the event time")
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle updates an existing subscription and advances the watermark`() {
        gateway.event = subscriptionEvent(status = "past_due", priceId = "price_business")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), plan = Plan.PRO)
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("past_due", saved.captured.status)
        assertEquals(Plan.BUSINESS, saved.captured.plan)
        assertEquals(eventCreated, saved.captured.stripeEventAt)
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle is a no-op when the event was already processed`() {
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = now.minusSeconds(30))

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify(exactly = 0) { stripeEventRepository.markProcessedAndRedact(any(), any()) }
    }

    @Test
    fun `handle skips an out-of-order event but still stamps it processed`() {
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        // Last applied event is NEWER than this one → this redelivery must not clobber newer state.
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.plusSeconds(60))

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle records a non-subscription event but changes no billing state`() {
        gateway.event =
            StripeWebhookEvent(
                id = "evt_1",
                type = "invoice.paid",
                created = eventCreated,
                payload = "{}",
                data = StripeEventData.Ignored,
            )
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle applies a same-second cancellation instead of dropping it`() {
        // A `deleted`/`canceled` arriving in the SAME epoch-second as the last applied update must NOT
        // be treated as out-of-order (Stripe `created` is second-granular) — dropping it would leave a
        // canceled subscription stranded active. The watermark equals the event time (a tie).
        gateway.event = subscriptionEvent(status = "canceled")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated, status = "active")
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("canceled", saved.captured.status, "the same-second cancel is applied, not skipped")
        assertEquals(eventCreated, saved.captured.stripeEventAt)
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle propagates an apply failure without stamping the event processed`() {
        // Core invariant of the two-transaction design: a failed apply leaves processed_at null (the
        // payload is retained) so Stripe redelivers. A save failure must propagate and never mark.
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { subscriptionRepository.findByStripeCustomerId("cus_1") } returns null
        every { subscriptionRepository.save(any()) } throws RuntimeException("db down")

        assertThrows<RuntimeException> { service.handle("{}", "sig") }

        verify(exactly = 0) { stripeEventRepository.markProcessedAndRedact(any(), any()) }
    }

    @Test
    fun `handle drops an unattributable event with no row and no user metadata`() {
        gateway.event = subscriptionEvent(userId = null, customerId = null)
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns null

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        // Still stamped processed so Stripe stops retrying an event we structurally can't use.
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle propagates a signature failure and records nothing`() {
        gateway.signatureError = true

        assertThrows<WebhookSignatureException> { service.handle("{}", "bad-sig") }

        verify(exactly = 0) { stripeEventRepository.insertIfAbsent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a first active subscription publishes a subscription-activated email`() {
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { subscriptionRepository.findByStripeCustomerId("cus_1") } returns null
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        service.handle("{}", "sig")

        verify(exactly = 1) { eventPublisher.publishEvent(SubscriptionActivated(userId, Plan.PRO)) }
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is PaymentIssue }) }
    }

    @Test
    fun `a transition into past_due publishes a payment-issue email`() {
        gateway.event = subscriptionEvent(status = "past_due")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), plan = Plan.PRO, status = "active")
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        service.handle("{}", "sig")

        verify(exactly = 1) { eventPublisher.publishEvent(PaymentIssue(userId)) }
        // The account was already active, so re-activating it must NOT re-mail.
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is SubscriptionActivated }) }
    }

    @Test
    fun `recovery from past_due back to active publishes a subscription-activated email`() {
        gateway.event = subscriptionEvent(status = "active")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), plan = Plan.PRO, status = "past_due")
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        service.handle("{}", "sig")

        verify(exactly = 1) { eventPublisher.publishEvent(SubscriptionActivated(userId, Plan.PRO)) }
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is PaymentIssue }) }
    }

    @Test
    fun `re-applying an already-active subscription sends no email`() {
        gateway.event = subscriptionEvent(status = "active")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), plan = Plan.PRO, status = "active")
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        service.handle("{}", "sig")

        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is SubscriptionActivated }) }
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is PaymentIssue }) }
    }

    @Test
    fun `a skipped out-of-order event publishes no email`() {
        gateway.event = subscriptionEvent()
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.plusSeconds(60))

        service.handle("{}", "sig")

        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is SubscriptionActivated }) }
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is PaymentIssue }) }
    }

    private companion object {
        private const val THIRTY_DAYS_SECONDS = 2_592_000L
        private const val ONE_DAY_SECONDS = 86_400L
    }
}
