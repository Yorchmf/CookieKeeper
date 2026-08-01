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
import kotlin.test.assertFalse
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
    fun `stripe events dedupe on the stripe event id`() {
        stripeEventRepository.saveAndFlush(
            StripeEventEntity(
                stripeEventId = "evt_1",
                type = "customer.subscription.updated",
                payload = "{\"id\":\"evt_1\"}",
                receivedAt = now,
                processedAt = null,
            ),
        )

        assertTrue(stripeEventRepository.existsByStripeEventId("evt_1"))
        assertFalse(stripeEventRepository.existsByStripeEventId("evt_2"))

        assertThrows<DataIntegrityViolationException> {
            stripeEventRepository.saveAndFlush(
                StripeEventEntity(
                    stripeEventId = "evt_1",
                    type = "customer.subscription.deleted",
                    payload = "{\"id\":\"evt_1\",\"dup\":true}",
                    receivedAt = now,
                    processedAt = now,
                ),
            )
        }
    }
}
