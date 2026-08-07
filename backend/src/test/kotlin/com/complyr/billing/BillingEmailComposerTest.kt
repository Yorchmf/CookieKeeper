package com.complyr.billing

import com.complyr.common.ComplyrProperties
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertTrue

/**
 * Renders the real `messages/notify_*` bundles through the billing composer. Resolving every locale
 * doubles as a parity check: a missing `subscriptionActivated.*`/`paymentIssue.*`/`trialEnding.*` key
 * (or a stray un-doubled apostrophe, which MessageFormat would choke on) in any bundle surfaces here.
 */
class BillingEmailComposerTest {
    private val messageSource =
        ResourceBundleMessageSource().apply {
            setBasename("messages/notify")
            setDefaultEncoding("UTF-8")
        }

    private val properties =
        ComplyrProperties(
            auth =
                ComplyrProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "https://app.complyr.eu",
            cdnBaseUrl = "https://cdn.complyr.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    // Fixed UTC clock: the trial-ending email renders a localized DATE, so the assertion below would
    // otherwise depend on the machine's zone (and drift a day for anyone east/west of UTC).
    private val composer =
        BillingEmailComposer(
            messageSource,
            properties,
            Clock.fixed(Instant.parse("2026-08-07T09:00:00Z"), ZoneOffset.UTC),
        )

    private val supportedLocales = listOf("en", "de", "fr", "es", "it")

    @Test
    fun `subscription-activated email links to the localized billing page and names the plan`() {
        val email = composer.subscriptionActivatedEmail("en", Plan.PRO)

        assertTrue(email.subject.contains("Pro"), "subject should name the plan tier: ${email.subject}")
        assertTrue(
            email.htmlBody.contains("https://app.complyr.eu/en/billing"),
            "activated body must link to the localized billing page: ${email.htmlBody}",
        )
        assertTrue(email.htmlBody.contains("Pro"), "activated body should name the plan tier: ${email.htmlBody}")
    }

    @Test
    fun `payment-issue email links to the localized billing page`() {
        val email = composer.paymentIssueEmail("de")

        assertTrue(email.subject.isNotBlank())
        assertTrue(
            email.htmlBody.contains("https://app.complyr.eu/de/billing"),
            "payment-issue body must link to the localized billing page: ${email.htmlBody}",
        )
    }

    /**
     * The reminder's whole job is to name the day the trial lapses, so the date must be written the way
     * the reader writes dates — not merely translated around a fixed format. Asserting the ORDERING
     * (`August 21` vs `21. August`) rather than an exact string keeps this honest about the behaviour
     * while staying stable across CLDR revisions.
     */
    @Test
    fun `trial-ending email writes the end date the way the reader's locale does`() {
        val trialEndsAt = Instant.parse("2026-08-21T09:00:00Z")

        val english = composer.trialEndingEmail("en", trialEndsAt)
        val german = composer.trialEndingEmail("de", trialEndsAt)

        assertTrue(english.htmlBody.contains("August 21"), "en should read month-first: ${english.htmlBody}")
        assertTrue(german.htmlBody.contains("21. August"), "de should read day-first: ${german.htmlBody}")
        assertTrue(english.htmlBody.contains("2026"), "the year must be present: ${english.htmlBody}")
        assertTrue(
            english.htmlBody.contains("https://app.complyr.eu/en/billing"),
            "trial-ending body must link to the localized billing page: ${english.htmlBody}",
        )
    }

    @Test
    fun `all billing emails resolve for every supported locale`() {
        val trialEndsAt = Instant.parse("2026-08-21T09:00:00Z")

        supportedLocales.forEach { locale ->
            val activated = composer.subscriptionActivatedEmail(locale, Plan.BUSINESS)
            val paymentIssue = composer.paymentIssueEmail(locale)
            val trialEnding = composer.trialEndingEmail(locale, trialEndsAt)

            assertTrue(activated.subject.isNotBlank(), "subscriptionActivated.subject missing for locale=$locale")
            assertTrue(activated.htmlBody.contains("/$locale/billing"), "activated link missing for locale=$locale")
            assertTrue(activated.htmlBody.contains("Business"), "activated plan name missing for locale=$locale")
            assertTrue(paymentIssue.subject.isNotBlank(), "paymentIssue.subject missing for locale=$locale")
            assertTrue(paymentIssue.htmlBody.contains("/$locale/billing"), "paymentIssue link missing for locale=$locale")
            assertTrue(trialEnding.subject.isNotBlank(), "trialEnding.subject missing for locale=$locale")
            assertTrue(trialEnding.htmlBody.contains("/$locale/billing"), "trialEnding link missing for locale=$locale")
            assertTrue(trialEnding.htmlBody.contains("2026"), "trialEnding date missing for locale=$locale")
        }
    }
}
