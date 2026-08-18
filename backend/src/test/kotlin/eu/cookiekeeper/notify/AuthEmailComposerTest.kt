package eu.cookiekeeper.notify

import eu.cookiekeeper.common.CookieKeeperProperties
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the real `messages/notify_*` bundles through the composer. Resolving every locale doubles as
 * a parity check: a missing `welcome.*` key in any bundle throws `NoSuchMessageException` here.
 */
class AuthEmailComposerTest {
    private val messageSource =
        ResourceBundleMessageSource().apply {
            setBasename("messages/notify")
            setDefaultEncoding("UTF-8")
        }

    private val properties =
        CookieKeeperProperties(
            auth =
                CookieKeeperProperties.Auth(
                    jwtSecret = "unit-test-jwt-secret-0123456789-abcdefghijklmnop",
                    accessTokenTtl = Duration.ofMinutes(15),
                    refreshTokenTtl = Duration.ofDays(30),
                    verificationTokenTtl = Duration.ofHours(24),
                    resetTokenTtl = Duration.ofHours(1),
                ),
            appBaseUrl = "https://app.cookiekeeper.eu",
            cdnBaseUrl = "https://cdn.cookiekeeper.eu",
            mailFrom = "no-reply@complyr.eu",
        )

    private val composer = AuthEmailComposer(messageSource, properties)

    private val supportedLocales = listOf("en", "de", "fr", "es", "it")

    @Test
    fun `welcome email links to the localized dashboard home`() {
        val email = composer.welcomeEmail("de")

        assertTrue(email.subject.isNotBlank())
        assertTrue(
            email.htmlBody.contains("https://app.cookiekeeper.eu/de"),
            "welcome body must link to the localized dashboard: ${email.htmlBody}",
        )
    }

    @Test
    fun `welcome email resolves a non-blank subject and correct link for every supported locale`() {
        supportedLocales.forEach { locale ->
            val email = composer.welcomeEmail(locale)

            assertTrue(email.subject.isNotBlank(), "welcome.subject missing for locale=$locale")
            assertTrue(
                email.htmlBody.contains("https://app.cookiekeeper.eu/$locale"),
                "welcome link missing for locale=$locale: ${email.htmlBody}",
            )
        }
    }

    @Test
    fun `auth subjects contain no doubled apostrophe in any locale`() {
        // Auth subjects are resolved with null args, so MessageFormat never runs on them — a translator
        // who "escaped" an apostrophe as '' (correct only for MessageFormat-processed strings, like the
        // bodies and the billing subjects) would ship a literal '' here. Guard against that regression.
        supportedLocales.forEach { locale ->
            val subjects =
                listOf(
                    composer.verificationEmail(locale, "t").subject,
                    composer.passwordResetEmail(locale, "t").subject,
                    composer.welcomeEmail(locale).subject,
                )
            subjects.forEach { subject ->
                assertTrue(!subject.contains("''"), "auth subject has a literal '' for locale=$locale: $subject")
            }
        }
    }

    @Test
    fun `verification and reset emails still embed the tokenized link`() {
        val verification = composer.verificationEmail("en", "raw-verify-token")
        val reset = composer.passwordResetEmail("en", "raw-reset-token")

        assertEquals("Confirm your email address", verification.subject)
        assertTrue(
            verification.htmlBody.contains("https://app.cookiekeeper.eu/en/verify-email?token=raw-verify-token"),
        )
        assertTrue(
            reset.htmlBody.contains("https://app.cookiekeeper.eu/en/reset-password?token=raw-reset-token"),
        )
    }
}
