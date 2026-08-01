package com.complyr.billing

import com.complyr.TestcontainersConfiguration
import com.complyr.auth.UserEntity
import com.complyr.auth.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class BillingRepositoryIntegrationTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    private lateinit var stripeEventRepository: StripeEventRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun newUser(): UserEntity =
        userRepository.saveAndFlush(
            UserEntity(email = "user-${UUID.randomUUID()}@example.com", passwordHash = "hash"),
        )

    private fun newSubscription(
        userId: UUID,
        plan: Plan = Plan.PRO,
        status: String = "active",
        stripeSubId: String? = "sub_${UUID.randomUUID()}",
        stripeCustomerId: String? = "cus_${UUID.randomUUID()}",
    ): SubscriptionEntity =
        subscriptionRepository.saveAndFlush(
            SubscriptionEntity(
                userId = userId,
                stripeCustomerId = stripeCustomerId,
                stripeSubId = stripeSubId,
                plan = plan,
                status = status,
                periodEnd = now.plusSeconds(2_592_000),
                createdAt = now,
                updatedAt = now,
            ),
        )

    @Test
    fun `subscription round-trips and the plan enum persists as its name`() {
        val user = newUser()
        val saved = newSubscription(user.id, plan = Plan.BUSINESS, stripeSubId = "sub_abc", stripeCustomerId = "cus_abc")

        assertEquals(saved.id, subscriptionRepository.findByUserId(user.id)?.id)
        assertEquals(Plan.BUSINESS, subscriptionRepository.findByStripeSubId("sub_abc")?.plan)
        assertEquals(saved.id, subscriptionRepository.findByStripeCustomerId("cus_abc")?.id)

        // Lock the @Enumerated(STRING) contract: the raw column must hold the enum NAME, not an
        // ordinal. An accidental EnumType.ORDINAL would round-trip identically, so assert the column.
        val rawPlan =
            entityManager
                .createNativeQuery("SELECT plan FROM subscriptions WHERE id = :id")
                .setParameter("id", saved.id)
                .singleResult
        assertEquals("BUSINESS", rawPlan)
    }

    @Test
    fun `finders miss when nothing matches`() {
        assertNull(subscriptionRepository.findByUserId(UUID.randomUUID()))
        assertNull(subscriptionRepository.findByStripeSubId("sub_missing"))
        assertNull(subscriptionRepository.findByStripeCustomerId("cus_missing"))
    }

    @Test
    fun `a second subscription for the same user violates the per-user unique constraint`() {
        val user = newUser()
        newSubscription(user.id)

        assertThrows<DataIntegrityViolationException> { newSubscription(user.id) }
    }

    @Test
    fun `the same stripe subscription id can't be logged twice`() {
        newSubscription(newUser().id, stripeSubId = "sub_dup")

        assertThrows<DataIntegrityViolationException> { newSubscription(newUser().id, stripeSubId = "sub_dup") }
    }

    @Test
    fun `insertIfAbsent claims an event id once and no-ops on Stripe re-delivery`() {
        val inserted =
            stripeEventRepository.insertIfAbsent(
                id = UUID.randomUUID(),
                stripeEventId = "evt_1",
                type = "customer.subscription.updated",
                payload = "{\"id\":\"evt_1\"}",
                receivedAt = now,
            )
        assertEquals(1, inserted, "first insert of an id claims it")

        // A re-delivery of the SAME id changes nothing and must not overwrite the original row.
        val redelivered =
            stripeEventRepository.insertIfAbsent(
                id = UUID.randomUUID(),
                stripeEventId = "evt_1",
                type = "customer.subscription.deleted",
                payload = "{\"id\":\"evt_1\",\"dup\":true}",
                receivedAt = now.plusSeconds(1),
            )
        assertEquals(0, redelivered, "re-delivery of a claimed id inserts nothing")

        entityManager.clear()
        val row = requireNotNull(stripeEventRepository.findByStripeEventId("evt_1"))
        assertEquals("customer.subscription.updated", row.type, "original row survives the re-delivery")
        assertEquals("{\"id\":\"evt_1\"}", row.payload)
        assertNull(row.processedAt)
        assertNull(stripeEventRepository.findByStripeEventId("evt_missing"))
    }

    @Test
    fun `markProcessedAndRedact stamps processed_at, nulls the payload, and is a one-time winner`() {
        stripeEventRepository.insertIfAbsent(
            id = UUID.randomUUID(),
            stripeEventId = "evt_2",
            type = "customer.subscription.updated",
            payload = "{\"email\":\"owner@example.com\"}",
            receivedAt = now,
        )

        val first = stripeEventRepository.markProcessedAndRedact("evt_2", now.plusSeconds(5))
        assertEquals(1, first, "the first mark wins")
        // A concurrent duplicate finds processed_at already set (guarded by `processed_at IS NULL`).
        val second = stripeEventRepository.markProcessedAndRedact("evt_2", now.plusSeconds(10))
        assertEquals(0, second, "a second mark is a no-op")

        entityManager.clear()
        val row = requireNotNull(stripeEventRepository.findByStripeEventId("evt_2"))
        assertEquals(now.plusSeconds(5), row.processedAt)
        assertNull(row.payload, "the raw body is redacted on process — no PII retained")
    }

    @Test
    fun `deleteBatchReceivedBefore removes only rows older than the cutoff, bounded by the batch size`() {
        stripeEventRepository.insertIfAbsent(UUID.randomUUID(), "evt_old_1", "t", "{}", now.minusSeconds(100))
        stripeEventRepository.insertIfAbsent(UUID.randomUUID(), "evt_old_2", "t", "{}", now.minusSeconds(90))
        stripeEventRepository.insertIfAbsent(UUID.randomUUID(), "evt_new", "t", "{}", now.plusSeconds(100))

        // Batch cap honored: only the single oldest row goes in the first pass.
        assertEquals(1, stripeEventRepository.deleteBatchReceivedBefore(now, batchSize = 1))
        // The remaining older row drains in the next pass; the newer-than-cutoff row is untouched.
        assertEquals(1, stripeEventRepository.deleteBatchReceivedBefore(now, batchSize = 10))

        entityManager.clear()
        assertNull(stripeEventRepository.findByStripeEventId("evt_old_1"))
        assertNull(stripeEventRepository.findByStripeEventId("evt_old_2"))
        assertNotNull(stripeEventRepository.findByStripeEventId("evt_new"), "rows newer than the cutoff survive")
    }

    @Test
    fun `tryAcquireAdvisoryXactLock grants the reaper lock inside the transaction`() {
        assertTrue(stripeEventRepository.tryAcquireAdvisoryXactLock(StripeWebhookReaper.ADVISORY_LOCK_KEY))
    }

    @Test
    fun `acquireSubscriptionLock takes the per-subscription advisory lock and returns a mappable result`() {
        // The native `SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key))` wrapper must execute
        // and map cleanly (the wrapping count gives the void lock function a non-void result).
        assertEquals(1L, subscriptionRepository.acquireSubscriptionLock(123_456_789L))
    }
}
