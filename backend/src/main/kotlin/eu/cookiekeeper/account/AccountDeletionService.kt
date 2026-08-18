package eu.cookiekeeper.account

import eu.cookiekeeper.account.dto.AccountDeletionResponse
import eu.cookiekeeper.auth.UserEntity
import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.billing.StripeGateway
import eu.cookiekeeper.billing.SubscriptionEntity
import eu.cookiekeeper.billing.SubscriptionRepository
import eu.cookiekeeper.common.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * "Delete my account" — GDPR Art. 17 erasure (ADR-20).
 *
 * The shape of this is dictated by one hard constraint: `consent_events.site_id` is ON DELETE RESTRICT
 * (V3) and CLAUDE.md #3 forbids deleting consent rows from application code at all. Deleting the `users`
 * row would cascade its `sites` straight into that RESTRICT, so a plain DELETE is not merely undesirable
 * — it is impossible for any account that ever served a banner. We therefore erase everything the account
 * owns and leave two anonymized tombstones (the user row and any consent-bearing site) purely to keep the
 * audit evidence referentially valid until it ages out on the 3-year partition schedule (ADR-16).
 *
 * Sequencing is deliberate and the two halves must not be merged:
 *
 *  1. **Re-authenticate**, then **cancel at Stripe** — outside any transaction. A Stripe failure aborts
 *     here with nothing erased. The reverse order could leave a deleted account still being billed with
 *     the subscription id already gone, which is unrecoverable without a manual Stripe hunt.
 *  2. **Erase**, in a single transaction, so an account is never left half-deleted.
 *
 * A `customer.subscription.deleted` webhook then arrives for a row that no longer exists; the webhook
 * handler already logs and drops unattributable events, so no special case is needed there.
 */
@Service
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val siteErasure: AccountSiteErasureRepository,
    private val identityErasure: AccountIdentityErasureRepository,
    private val stripeGateway: StripeGateway,
    private val passwordService: AccountPasswordService,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(AccountDeletionService::class.java)

    // The erasure runs through an explicit template rather than @Transactional because it must start
    // AFTER the Stripe call returns — a self-invoked @Transactional method would not be proxied anyway,
    // and holding a transaction open across a third-party HTTP round trip is exactly what we're avoiding.
    private val erasureTx = TransactionTemplate(transactionManager)

    fun delete(
        userId: UUID,
        password: String,
    ): AccountDeletionResponse {
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        // A tombstone is not an account: a still-valid access JWT minted just before an erasure must not
        // be able to run one again (the second pass would be harmless, but the answer is "no such user").
        if (user.isErased) throw UnauthenticatedException()
        passwordService.confirm(user, password) { DeleteConfirmationFailedException() }

        val subscription = subscriptionRepository.findByUserId(userId)
        cancelBillingAtStripe(subscription)

        val result =
            erasureTx.execute { erase(user, subscription) }
                ?: error("Account erasure transaction returned no result")
        // Deliberately id-only: an erasure log line that named the email would re-create the very PII the
        // operation exists to destroy (CLAUDE.md #4).
        log.info(
            "Erased account {} (sites deleted={}, sites anonymized={})",
            userId,
            result.sitesDeleted,
            result.sitesAnonymized,
        )
        return result
    }

    /** Cancels the live Stripe subscription, if any. Never reached for an account that never subscribed. */
    private fun cancelBillingAtStripe(subscription: SubscriptionEntity?) {
        val subscriptionId = subscription?.stripeSubId ?: return
        stripeGateway.cancelSubscription(subscriptionId)
    }

    /**
     * The whole erasure, in dependency order. Runs as one transaction: either the account is fully erased
     * or nothing changed.
     *
     * Everything the account owned is DELETED. Only two rows survive, both stripped of personal data:
     * sites that hold consent evidence, and the user row those sites reference.
     */
    private fun erase(
        user: UserEntity,
        subscription: SubscriptionEntity?,
    ): AccountDeletionResponse {
        val userId = user.id
        val now = clock.instant()

        // The SAME per-user advisory lock the site-create path takes before its cap check
        // ([eu.cookiekeeper.billing.EntitlementService.requireCanAddSite]). Without it a create racing this
        // transaction can commit an ACTIVE site a moment after the erasure swept the table — a live site
        // owned by a tombstone, unreachable from any dashboard and impossible to erase a second time.
        // The create side re-reads the user under this lock, so one of the two always loses cleanly.
        siteErasure.acquireUserSiteLock(siteLockKey(userId))

        siteErasure.deleteJobs(userId)
        siteErasure.deleteScanCookies(userId)
        siteErasure.deleteScans(userId)
        siteErasure.deleteCookieOverrides(userId)
        siteErasure.deletePolicies(userId)
        siteErasure.deletePolicySettings(userId)
        siteErasure.deleteBannerConfigs(userId)

        identityErasure.deleteAuthTokens(userId)
        identityErasure.deleteRefreshTokens(userId)
        identityErasure.deleteNotificationPreferences(userId)
        identityErasure.deleteSubscriptions(userId)
        identityErasure.deletePublicScanLeads(user.email)
        redactPendingStripePayloads(user, subscription, now)

        val sitesDeleted = siteErasure.deleteSitesWithoutConsentEvidence(userId)
        val sitesAnonymized = siteErasure.tombstoneRemainingSites(userId, now)

        userRepository.save(tombstone(user, now))

        return AccountDeletionResponse(sitesDeleted = sitesDeleted, sitesAnonymized = sitesAnonymized)
    }

    /**
     * Scrubs any Stripe webhook body still awaiting processing that mentions this account.
     *
     * `stripe_events` keeps the raw request body only until the handler applies the event, at which point
     * it is nulled (redact-on-process, V13). An *unprocessed* row therefore still holds Stripe's verbatim
     * JSON — which for checkout/subscription events includes `customer_email`. Those rows carry no user
     * id, so nothing else in the erasure would ever reach them; the account's own email and Stripe
     * customer id are the only handles we have, and a substring match on them is exact enough (both are
     * unique strings, neither is a pattern).
     *
     * They are stamped processed rather than deleted: keeping the id is what makes a Stripe re-delivery
     * dedupe away instead of re-inserting the same body. Skipping the event itself costs nothing — the
     * subscription row it would have updated was deleted moments ago, and the handler already logs and
     * drops events it cannot attribute.
     */
    private fun redactPendingStripePayloads(
        user: UserEntity,
        subscription: SubscriptionEntity?,
        now: Instant,
    ) {
        val handles = listOfNotNull(user.email, subscription?.stripeCustomerId, subscription?.stripeSubId)
        handles.forEach { identityErasure.redactPendingStripeEvents(it, now) }
    }

    // Mirrors eu.cookiekeeper.billing.EntitlementService.advisoryLockKey — the two MUST fold the user id the
    // same way or the erasure and the site-create cap check would take locks in different key spaces and
    // never serialize against each other.
    private fun siteLockKey(userId: UUID): Long = userId.mostSignificantBits xor userId.leastSignificantBits

    /**
     * The user row with every personal field destroyed. What remains is derived from the account's own
     * random UUID, which is not personal data on its own — it identifies a row, not a person, and the rows
     * it still reaches (archived site tombstones, and through them consent events) carry nothing that
     * links back either.
     *
     * The email must stay unique (`uq_users_email`) and must be one nobody can ever hold, hence the
     * RFC 2606 `.invalid` TLD. For the password see
     * [AccountPasswordService.unmatchableHash][eu.cookiekeeper.account.AccountPasswordService.unmatchableHash].
     */
    private fun tombstone(
        user: UserEntity,
        now: Instant,
    ): UserEntity =
        user.copy(
            email = "erased-${user.id}@erased.invalid",
            passwordHash = passwordService.unmatchableHash(),
            name = null,
            locale = DEFAULT_LOCALE,
            verifiedAt = null,
            failedLoginAttempts = 0,
            lockedUntil = null,
            trialEndingEmailSentAt = null,
            // A pending email change parks a second real address (V24) — it is personal data and must not
            // survive on the tombstone. The email_change token itself is already gone via deleteAuthTokens.
            pendingEmail = null,
            deletedAt = now,
        )

    private companion object {
        const val DEFAULT_LOCALE = "en"
    }
}
