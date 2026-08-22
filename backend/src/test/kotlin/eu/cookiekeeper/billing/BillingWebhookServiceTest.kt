package eu.cookiekeeper.billing

import eu.cookiekeeper.common.CookieKeeperProperties
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

        override fun cancelSubscription(subscriptionId: String) = error("unused")

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
                    priceIds =
                        CookieKeeperProperties.Billing.PriceIds(
                            starter = "price_starter",
                            pro = "price_pro",
                            business = "price_business",
                        ),
                ),
            appBaseUrl = "https://app.cookiekeeper.test",
            cdnBaseUrl = "https://cdn.cookiekeeper.test",
            mailFrom = "support@cookiekeeper.test",
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

    // Default lineage marker for "sub_1" — well before eventCreated, so tests overriding eventCreated
    // via the watermark parameter still have a sane, earlier subscription-creation time.
    private val sub1Created = eventCreated.minusSeconds(ONE_DAY_SECONDS)

    @BeforeEach
    fun stubSubscriptionLock() {
        // Non-relaxed mock: the account advisory lock taken at the top of applySubscription must be
        // stubbed for every path that reaches it.
        every { subscriptionRepository.acquireSubscriptionLock(any()) } returns 1L
    }

    private fun subscriptionEvent(
        userId: UUID? = this.userId,
        subscriptionId: String = "sub_1",
        customerId: String? = "cus_1",
        status: String = "active",
        priceId: String? = "price_pro",
        eventId: String = "evt_1",
        subscriptionCreatedAt: Instant = sub1Created,
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
                subscriptionCreatedAt = subscriptionCreatedAt,
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
        stripeSubCreatedAt: Instant? = sub1Created,
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
        stripeSubCreatedAt = stripeSubCreatedAt,
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
    fun `handle does not let a same-second non-terminal event resurrect a canceled subscription`() {
        // The reverse of the same-second cancel: the terminal `canceled` landed FIRST (watermark ==
        // event time), then a same-second `active`/`updated` arrives reordered. Applying it would hand
        // entitlement back to a canceled account with no later event to correct it — so it must be
        // skipped, yet still stamped processed so Stripe stops retrying.
        gateway.event = subscriptionEvent(status = "active")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated, status = "canceled")

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(match<Any> { it is SubscriptionActivated }) }
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle applies a genuinely-later reactivation after a cancellation`() {
        // A real re-subscribe on the same row carries a strictly-later `created`, so the terminal guard
        // must NOT block it — entitlement is legitimately restored.
        gateway.event = subscriptionEvent(status = "active")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "canceled")
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("active", saved.captured.status, "a strictly-later reactivation is applied")
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
    fun `handle skips a late event for a superseded subscription even after the current one turns past_due`() {
        // The row already moved on to a NEWER-lineage subscription (a re-subscribe), which has since
        // gone past_due on its own. A stale dunning-conclusion event for the OLD, OLDER-lineage
        // subscription — carrying a LATER event `created` than our watermark — must still not clobber
        // the row: lineage, not current status, is what makes it foreign.
        gateway.event =
            subscriptionEvent(
                subscriptionId = "sub_old",
                status = "canceled",
                eventId = "evt_late",
                subscriptionCreatedAt = sub1Created.minusSeconds(THIRTY_DAYS_SECONDS),
            )
        every { stripeEventRepository.findByStripeEventId("evt_late") } returns
            recorded(processedAt = null).copy(stripeEventId = "evt_late")
        every { subscriptionRepository.findByStripeSubId("sub_old") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "past_due")
                .copy(stripeSubId = "sub_new")

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        verify { stripeEventRepository.markProcessedAndRedact("evt_late", now) }
    }

    @Test
    fun `handle applies a new subscription's own event even while the old row still reads active`() {
        // Out-of-order delivery: the NEW subscription's first event arrives BEFORE the old
        // subscription's terminal event has landed, so the row still shows the old id as active. Since
        // "sub_new" is a genuinely later lineage (subscriptionCreatedAt after the tracked one), it must
        // apply immediately rather than being dropped as "foreign" — a customer's real re-subscribe
        // must never be silently discarded just because Stripe delivered it first.
        gateway.event =
            subscriptionEvent(
                subscriptionId = "sub_new",
                status = "active",
                subscriptionCreatedAt = eventCreated.plusSeconds(60),
            )
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_new") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "active")
                .copy(stripeSubId = "sub_old")
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("sub_new", saved.captured.stripeSubId)
        assertEquals("active", saved.captured.status)
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle adopts a new subscription id when the account's current subscription is past_due`() {
        // A genuine re-subscribe: the new subscription's lineage is later, so adopting it applies
        // regardless of the old subscription's current status.
        gateway.event = subscriptionEvent(subscriptionId = "sub_new", status = "active", subscriptionCreatedAt = eventCreated)
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_new") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "past_due")
                .copy(stripeSubId = "sub_old")
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("sub_new", saved.captured.stripeSubId)
        assertEquals("active", saved.captured.status)
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle adopts a new subscription id when the account's current subscription is already canceled`() {
        gateway.event = subscriptionEvent(subscriptionId = "sub_new", status = "active", subscriptionCreatedAt = eventCreated)
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_new") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "canceled")
                .copy(stripeSubId = "sub_old")
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("sub_new", saved.captured.stripeSubId)
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle drops a same-created-second differing subscription id as foreign, not as a new lineage`() {
        // Two different subscription ids should never share a creation instant in practice; if they
        // somehow did, the conservative reading (not-after ⇒ foreign) is the safe one.
        gateway.event = subscriptionEvent(subscriptionId = "sub_tied", status = "active", subscriptionCreatedAt = sub1Created)
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_tied") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(stripeEventAt = eventCreated.minusSeconds(60), status = "active")

        service.handle("{}", "sig")

        verify(exactly = 0) { subscriptionRepository.save(any()) }
        verify { stripeEventRepository.markProcessedAndRedact("evt_1", now) }
    }

    @Test
    fun `handle applies over an unknown-lineage existing row rather than rejecting a foreign subscription id`() {
        // Pinned, ACCEPTED-RISK behavior (see the V29 migration comment and isOlderSubscriptionLineage's
        // doc): a row written before the lineage column existed has no marker to compare against, so a
        // differing subscription id is treated as "not older" and applied — even though, in the exact
        // scenario this feature exists to prevent, that event could be a stale cross-subscription
        // clobber. This closes once the row's own next event stamps a real lineage marker. If this test
        // starts failing, that is a deliberate policy change, not a regression to silently "fix."
        gateway.event = subscriptionEvent(subscriptionId = "sub_foreign", status = "canceled")
        every { stripeEventRepository.findByStripeEventId("evt_1") } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_foreign") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns
            existingSubscription(
                stripeEventAt = eventCreated.minusSeconds(60),
                status = "active",
                stripeSubCreatedAt = null,
            )
        val saved = slot<SubscriptionEntity>()
        every { subscriptionRepository.save(capture(saved)) } answers { saved.captured }

        service.handle("{}", "sig")

        assertEquals("sub_foreign", saved.captured.stripeSubId)
        assertEquals("canceled", saved.captured.status)
    }

    @Test
    fun `handle locks on the same key whether or not the event carries the user-id metadata`() {
        // accountLockKey prefers customerId over userId specifically so two of the same account's
        // subscriptions — one Checkout-created (carries userId metadata) and one created out-of-band
        // (does not) — still serialize against each other. Verified by asserting the SAME lock key is
        // derived for both, without needing to know the raw pre-hash string.
        val lockKeys = mutableListOf<Long>()
        every { subscriptionRepository.acquireSubscriptionLock(capture(lockKeys)) } returns 1L
        every { stripeEventRepository.findByStripeEventId(any()) } returns recorded(processedAt = null)
        every { subscriptionRepository.findByStripeSubId("sub_1") } returns null
        every { subscriptionRepository.findByUserId(userId) } returns null
        every { subscriptionRepository.findByStripeCustomerId("cus_shared") } returns null
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        gateway.event = subscriptionEvent(userId = userId, customerId = "cus_shared", eventId = "evt_a")
        service.handle("{}", "sig")
        gateway.event = subscriptionEvent(userId = null, customerId = "cus_shared", eventId = "evt_b")
        service.handle("{}", "sig")

        assertEquals(2, lockKeys.size)
        assertEquals(lockKeys[0], lockKeys[1], "same customerId must hash to the same lock key regardless of userId presence")
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
