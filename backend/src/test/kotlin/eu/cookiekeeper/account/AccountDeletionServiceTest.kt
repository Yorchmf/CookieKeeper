package eu.cookiekeeper.account

import eu.cookiekeeper.auth.LoginAttemptService
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.BillingUnavailableException
import eu.cookiekeeper.billing.Plan
import eu.cookiekeeper.billing.StripeGateway
import eu.cookiekeeper.billing.SubscriptionEntity
import eu.cookiekeeper.billing.SubscriptionRepository
import eu.cookiekeeper.common.UnauthenticatedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AccountDeletionService] — the Art. 17 erasure (ADR-20).
 *
 * The bulk DML lives in native queries that only a real Postgres can execute, so what is verified here is
 * what unit tests can actually decide: that re-authentication gates the whole thing, that billing is
 * cancelled BEFORE anything is destroyed (and a Stripe failure destroys nothing), and that the surviving
 * user row is a genuine tombstone rather than a lightly-edited account. The SQL itself is covered by the
 * Testcontainers integration test.
 */
class AccountDeletionServiceTest {
    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val siteErasure = mockk<AccountSiteErasureRepository>(relaxed = true)
    private val identityErasure = mockk<AccountIdentityErasureRepository>(relaxed = true)
    private val stripeGateway = mockk<StripeGateway>(relaxed = true)
    private val loginAttemptService = mockk<LoginAttemptService>(relaxed = true)
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    // A password encoder that matches only the literal correct password, so a wrong one is a real
    // mismatch rather than a mock returning whatever the test asked for.
    private val passwordEncoder =
        object : PasswordEncoder {
            override fun encode(rawPassword: CharSequence?): String = "hash:$rawPassword"

            override fun matches(
                rawPassword: CharSequence?,
                encodedPassword: String?,
            ): Boolean = encodedPassword == "hash:$rawPassword"
        }

    // Runs the callback inline: TransactionTemplate only needs a status object back.
    private val transactionManager =
        mockk<PlatformTransactionManager>(relaxed = true).also {
            every { it.getTransaction(any()) } returns SimpleTransactionStatus()
        }

    private val service =
        AccountDeletionService(
            userRepository = userRepository,
            subscriptionRepository = subscriptionRepository,
            siteErasure = siteErasure,
            identityErasure = identityErasure,
            stripeGateway = stripeGateway,
            passwordService =
                AccountPasswordService(
                    userRepository = userRepository,
                    passwordEncoder = passwordEncoder,
                    loginAttemptService = loginAttemptService,
                    tokenService = mockk(relaxed = true),
                    clock = clock,
                ),
            clock = clock,
            transactionManager = transactionManager,
        )

    @Test
    fun `erases the account and reports what happened to its sites`() {
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns null
        every { siteErasure.deleteSitesWithoutConsentEvidence(USER_ID) } returns 2
        every { siteErasure.tombstoneRemainingSites(USER_ID, NOW) } returns 1
        every { userRepository.save(any()) } returnsArgument 0

        val result = service.delete(USER_ID, PASSWORD)

        assertEquals(2, result.sitesDeleted)
        assertEquals(1, result.sitesAnonymized)
        verify { siteErasure.deleteScans(USER_ID) }
        verify { identityErasure.deleteRefreshTokens(USER_ID) }
        verify { identityErasure.deletePublicScanLeads(EMAIL) }
    }

    @Test
    fun `leaves no personal data on the surviving user row`() {
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns null
        every { siteErasure.deleteSitesWithoutConsentEvidence(USER_ID) } returns 0
        every { siteErasure.tombstoneRemainingSites(USER_ID, NOW) } returns 1
        val saved = slot<UserEntity>()
        every { userRepository.save(capture(saved)) } returnsArgument 0

        service.delete(USER_ID, PASSWORD)

        val tombstone = saved.captured
        assertEquals(USER_ID, tombstone.id, "the row must survive so consent-bearing sites stay valid")
        assertFalse(tombstone.email.contains(EMAIL), "the original address must be gone")
        assertTrue(tombstone.email.endsWith("@erased.invalid"))
        assertNotEquals(CORRECT_HASH, tombstone.passwordHash, "the credential must not survive")
        assertFalse(
            passwordEncoder.matches(PASSWORD, tombstone.passwordHash),
            "the old password must not still open the tombstone",
        )
        assertNull(tombstone.verifiedAt)
        assertNull(tombstone.lockedUntil)
        assertNull(tombstone.trialEndingEmailSentAt)
        assertNull(tombstone.pendingEmail, "a parked email-change address is personal data and must not survive")
        assertEquals(NOW, tombstone.deletedAt)
        assertTrue(tombstone.isErased)
    }

    @Test
    fun `cancels the Stripe subscription before erasing anything`() {
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns subscription()
        every { siteErasure.deleteSitesWithoutConsentEvidence(USER_ID) } returns 0
        every { siteErasure.tombstoneRemainingSites(USER_ID, NOW) } returns 0
        every { userRepository.save(any()) } returnsArgument 0

        service.delete(USER_ID, PASSWORD)

        verify(ordering = io.mockk.Ordering.ORDERED) {
            stripeGateway.cancelSubscription(STRIPE_SUB_ID)
            siteErasure.deleteScans(USER_ID)
            userRepository.save(any())
        }
    }

    @Test
    fun `a Stripe failure aborts before a single row is destroyed`() {
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns subscription()
        every { stripeGateway.cancelSubscription(STRIPE_SUB_ID) } throws BillingUnavailableException()

        assertThrows<BillingUnavailableException> { service.delete(USER_ID, PASSWORD) }

        verify(exactly = 0) { siteErasure.deleteScans(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `rejects a wrong password without touching Stripe or any data`() {
        givenUser(user())

        assertThrows<DeleteConfirmationFailedException> { service.delete(USER_ID, "not-the-password") }

        verify(exactly = 0) { stripeGateway.cancelSubscription(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `counts a wrong password against the same lockout budget as the login page`() {
        givenUser(user())

        assertThrows<DeleteConfirmationFailedException> { service.delete(USER_ID, "not-the-password") }

        // Without this the endpoint is an uncounted password oracle for anyone holding a stolen session.
        verify { loginAttemptService.recordFailure(USER_ID) }
    }

    @Test
    fun `a correct password clears a lingering failure counter, like a successful login`() {
        // user() carries a LAPSED lock (non-null lockedUntil in the past), so a proven password must
        // reset it — otherwise the account stays one failure from re-locking after this re-auth.
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns null
        every { siteErasure.deleteSitesWithoutConsentEvidence(USER_ID) } returns 0
        every { siteErasure.tombstoneRemainingSites(USER_ID, NOW) } returns 0
        every { userRepository.save(any()) } returnsArgument 0

        service.delete(USER_ID, PASSWORD)

        verify { loginAttemptService.clearFailures(USER_ID) }
    }

    @Test
    fun `refuses a locked account before spending a bcrypt comparison`() {
        givenUser(user().copy(lockedUntil = NOW.plusSeconds(60)))

        assertThrows<DeleteConfirmationFailedException> { service.delete(USER_ID, PASSWORD) }

        // Even the CORRECT password is refused while locked, and the attempt is not counted again —
        // otherwise a locked-out attacker could keep extending their own lock and burning bcrypt CPU.
        verify(exactly = 0) { loginAttemptService.recordFailure(any()) }
        verify(exactly = 0) { stripeGateway.cancelSubscription(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `scrubs any unprocessed Stripe webhook body that names the account`() {
        givenUser(user())
        every { subscriptionRepository.findByUserId(USER_ID) } returns subscription()
        every { siteErasure.deleteSitesWithoutConsentEvidence(USER_ID) } returns 0
        every { siteErasure.tombstoneRemainingSites(USER_ID, NOW) } returns 0
        every { userRepository.save(any()) } returnsArgument 0

        service.delete(USER_ID, PASSWORD)

        // Those rows carry no user id, so these three strings are the only handles the erasure has on the
        // raw Stripe JSON — which for checkout events embeds the customer's email.
        verify { identityErasure.redactPendingStripeEvents(EMAIL, NOW) }
        verify { identityErasure.redactPendingStripeEvents("cus_test", NOW) }
        verify { identityErasure.redactPendingStripeEvents(STRIPE_SUB_ID, NOW) }
    }

    @Test
    fun `refuses to run twice for an already-erased account`() {
        givenUser(user().copy(deletedAt = NOW))

        assertThrows<UnauthenticatedException> { service.delete(USER_ID, PASSWORD) }

        verify(exactly = 0) { stripeGateway.cancelSubscription(any()) }
    }

    private fun givenUser(user: UserEntity) {
        every { userRepository.findById(USER_ID) } returns Optional.of(user)
    }

    private fun user() =
        UserEntity(
            id = USER_ID,
            email = EMAIL,
            passwordHash = CORRECT_HASH,
            locale = "de",
            createdAt = NOW.minusSeconds(1_000),
            verifiedAt = NOW.minusSeconds(900),
            // A LAPSED lock: the field must be non-null so the tombstone assertions can prove it is
            // cleared, but a live one would now (correctly) refuse the deletion outright.
            lockedUntil = NOW.minusSeconds(60),
            trialEndingEmailSentAt = NOW.minusSeconds(100),
            // A change-of-email in flight, so the tombstone assertion can prove it is cleared.
            pendingEmail = "new-address@example.com",
        )

    private fun subscription() =
        SubscriptionEntity(
            userId = USER_ID,
            stripeCustomerId = "cus_test",
            stripeSubId = STRIPE_SUB_ID,
            plan = Plan.STARTER,
            status = "active",
            periodEnd = NOW.plusSeconds(86_400),
            createdAt = NOW.minusSeconds(1_000),
            updatedAt = NOW.minusSeconds(1_000),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T10:15:30Z")
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val EMAIL = "owner@example.com"
        const val PASSWORD = "correct horse battery staple"
        const val CORRECT_HASH = "hash:$PASSWORD"
        const val STRIPE_SUB_ID = "sub_test123"
    }
}
