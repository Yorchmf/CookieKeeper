package eu.cookiekeeper.billing

import eu.cookiekeeper.common.CookieKeeperProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Resolves an account's effective billing state ([AccountEntitlement]) from its subscription row and
 * the no-card trial window. The order is deliberate:
 *
 *  1. An active (or Stripe-`trialing`) subscription → [AccountEntitlement.Subscribed] — a paid plan
 *     always wins, even inside the original trial window.
 *  2. Otherwise, if we're still within `accountCreatedAt + trialPeriod` → [AccountEntitlement.Trial]
 *     (Starter-shaped limits, consent ingestion capped from config).
 *  3. Otherwise → [AccountEntitlement.Expired] (dashboard frozen, consent still recorded).
 *
 * The trial is derived from the user's creation time rather than a subscription row, so signup and
 * the consent path stay untouched by billing. [clock] is injected for deterministic time.
 */
@Service
class PlanResolver(
    private val properties: CookieKeeperProperties,
    private val clock: Clock,
) {
    fun resolve(
        accountCreatedAt: Instant,
        subscription: SubscriptionEntity?,
    ): AccountEntitlement {
        if (subscription != null && subscription.isActive) {
            return AccountEntitlement.Subscribed(subscription.plan)
        }
        val trialEndsAt = accountCreatedAt.plus(properties.billing.trialPeriod)
        return if (clock.instant().isBefore(trialEndsAt)) {
            AccountEntitlement.Trial(
                endsAt = trialEndsAt,
                entitlements =
                    Plan.STARTER.entitlements.copy(
                        consentEventCap = properties.billing.trialConsentEventCap,
                    ),
            )
        } else {
            AccountEntitlement.Expired
        }
    }
}
