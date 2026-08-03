package com.complyr.billing

import com.complyr.common.ComplyrProperties
import com.complyr.notify.ComposedEmail
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Localized billing emails (subscription activated, payment issue) from the same `messages/notify_*`
 * bundles (EN/DE/FR/ES/IT) the auth emails use. Links point at the dashboard billing page
 * (`complyr.app-base-url`) so the user can manage their subscription or fix their payment method.
 */
@Service
class BillingEmailComposer(
    private val messageSource: MessageSource,
    private val properties: ComplyrProperties,
) {
    fun subscriptionActivatedEmail(
        locale: String,
        plan: Plan,
    ): ComposedEmail = compose("subscriptionActivated", locale, planDisplayName(plan), billingLink(locale))

    fun paymentIssueEmail(locale: String): ComposedEmail = compose("paymentIssue", locale, billingLink(locale))

    private fun billingLink(locale: String): String = "${properties.appBaseUrl}/$locale/billing"

    /** Brand tier names are product proper nouns, not translated: STARTER → "Starter", PRO → "Pro". */
    private fun planDisplayName(plan: Plan): String = plan.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun compose(
        keyPrefix: String,
        locale: String,
        vararg args: String,
    ): ComposedEmail {
        val resolved = Locale.forLanguageTag(locale)
        return ComposedEmail(
            subject = messageSource.getMessage("$keyPrefix.subject", args, resolved),
            htmlBody = messageSource.getMessage("$keyPrefix.body", args, resolved),
        )
    }
}
