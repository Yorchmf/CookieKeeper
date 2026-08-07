package com.complyr.billing

import com.complyr.auth.UserRepository
import com.complyr.common.ComplyrProperties
import com.complyr.notify.BestEffortEmailDelivery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Composes and delivers billing lifecycle emails. The recipient's email + locale are resolved fresh
 * from the user row (never carried on the event), and delivery goes through the shared
 * [BestEffortEmailDelivery] contract — so a broken mail provider can never fail the webhook apply.
 * A missing user (e.g. deleted between webhook and send) is logged by id and skipped, never thrown.
 */
@Service
class BillingNotifier(
    private val composer: BillingEmailComposer,
    private val delivery: BestEffortEmailDelivery,
    private val userRepository: UserRepository,
    private val properties: ComplyrProperties,
) {
    private val log = LoggerFactory.getLogger(BillingNotifier::class.java)

    fun sendSubscriptionActivated(
        userId: UUID,
        plan: Plan,
    ) {
        val user = userRepository.findById(userId).orElse(null)
        if (user == null) {
            log.warn("Skipping subscription-activated email: no user {}", userId)
            return
        }
        delivery.deliver(userId, user.email, composer.subscriptionActivatedEmail(user.locale, plan), "subscription-activated")
    }

    fun sendPaymentIssue(userId: UUID) {
        val user = userRepository.findById(userId).orElse(null)
        if (user == null) {
            log.warn("Skipping payment-issue email: no user {}", userId)
            return
        }
        delivery.deliver(userId, user.email, composer.paymentIssueEmail(user.locale), "payment-issue")
    }

    /**
     * The trial-ending nudge. The end instant is DERIVED here (`created_at + trial-period`) rather than
     * carried on the event, for the same reason the address and locale are: the event is a fact about an
     * account, and everything else must reflect the account as it is at send time. It matches what
     * [PlanResolver] computes, so the email and the dashboard can never disagree about the date.
     */
    fun sendTrialEnding(userId: UUID) {
        val user = userRepository.findById(userId).orElse(null)
        if (user == null) {
            log.warn("Skipping trial-ending email: no user {}", userId)
            return
        }
        val trialEndsAt = user.createdAt.plus(properties.billing.trialPeriod)
        delivery.deliver(userId, user.email, composer.trialEndingEmail(user.locale, trialEndsAt), "trial-ending")
    }
}
