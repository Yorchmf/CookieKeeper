package com.complyr.billing

import com.complyr.common.ComplyrProperties
import com.complyr.notify.ComposedEmail
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Localized billing emails (subscription activated, payment issue, trial ending) from the same
 * `messages/notify_*` bundles (EN/DE/FR/ES/IT) the auth emails use. Links point at the dashboard
 * billing page (`complyr.app-base-url`) so the user can manage their subscription, fix their payment
 * method, or pick a plan before the trial lapses.
 */
@Service
class BillingEmailComposer(
    private val messageSource: MessageSource,
    private val properties: ComplyrProperties,
    private val clock: Clock,
) {
    fun subscriptionActivatedEmail(
        locale: String,
        plan: Plan,
    ): ComposedEmail = compose("subscriptionActivated", locale, planDisplayName(plan), billingLink(locale))

    fun paymentIssueEmail(locale: String): ComposedEmail = compose("paymentIssue", locale, billingLink(locale))

    fun trialEndingEmail(
        locale: String,
        trialEndsAt: Instant,
    ): ComposedEmail = compose("trialEnding", locale, formatDate(trialEndsAt, locale), billingLink(locale))

    /**
     * The trial end date as a reader in [locale] would write it. A formatted DATE rather than a
     * "3 days left" countdown on purpose: a count needs plural agreement in five languages, which
     * `java.text.MessageFormat` (what Spring's MessageSource uses) can only express as nested
     * `choice` clauses per bundle. A localized date sidesteps the grammar entirely and is the more
     * actionable fact anyway. Rendered in the clock's zone, the same zone the reminder cron fires in.
     */
    private fun formatDate(
        instant: Instant,
        locale: String,
    ): String =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.LONG)
            .withLocale(Locale.forLanguageTag(locale))
            .withZone(clock.zone)
            .format(instant)

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
