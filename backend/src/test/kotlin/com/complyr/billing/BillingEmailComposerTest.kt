package com.complyr.billing

import com.complyr.common.ComplyrProperties
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Duration
import kotlin.test.assertTrue

/**
 * Renders the real `messages/notify_*` bundles through the billing composer. Resolving every locale
 * doubles as a parity check: a missing `subscriptionActivated.*`/`paymentIssue.*` key (or a stray
 * un-doubled apostrophe, which MessageFormat would choke on) in any bundle surfaces here.
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

    private val composer = BillingEmailComposer(messageSource, properties)

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

    @Test
    fun `both billing emails resolve for every supported locale`() {
        supportedLocales.forEach { locale ->
            val activated = composer.subscriptionActivatedEmail(locale, Plan.BUSINESS)
            val paymentIssue = composer.paymentIssueEmail(locale)

            assertTrue(activated.subject.isNotBlank(), "subscriptionActivated.subject missing for locale=$locale")
            assertTrue(activated.htmlBody.contains("/$locale/billing"), "activated link missing for locale=$locale")
            assertTrue(activated.htmlBody.contains("Business"), "activated plan name missing for locale=$locale")
            assertTrue(paymentIssue.subject.isNotBlank(), "paymentIssue.subject missing for locale=$locale")
            assertTrue(paymentIssue.htmlBody.contains("/$locale/billing"), "paymentIssue link missing for locale=$locale")
        }
    }
}
