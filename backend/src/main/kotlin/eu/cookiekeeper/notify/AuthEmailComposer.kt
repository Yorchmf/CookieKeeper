package eu.cookiekeeper.notify

import eu.cookiekeeper.common.CookieKeeperProperties
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.util.Locale

data class ComposedEmail(
    val subject: String,
    val htmlBody: String,
)

/**
 * Localized auth emails (verification, password reset) from the `messages/notify_*`
 * bundles (EN/DE/FR/ES/IT). Links point at the dashboard (`cookiekeeper.app-base-url`).
 */
@Service
class AuthEmailComposer(
    private val messageSource: MessageSource,
    private val properties: CookieKeeperProperties,
) {
    fun verificationEmail(
        locale: String,
        rawToken: String,
    ): ComposedEmail = compose("verification", locale, link(locale, "verify-email", rawToken))

    fun passwordResetEmail(
        locale: String,
        rawToken: String,
    ): ComposedEmail = compose("reset", locale, link(locale, "reset-password", rawToken))

    /** First-verification welcome; links to the localized dashboard home, no token. */
    fun welcomeEmail(locale: String): ComposedEmail = compose("welcome", locale, "${properties.appBaseUrl}/$locale")

    /** Confirmation link for an email change, sent to the NEW address. */
    fun emailChangeEmail(
        locale: String,
        rawToken: String,
    ): ComposedEmail = compose("emailChange", locale, link(locale, "confirm-email-change", rawToken))

    /** Post-change security notice to the OLD address; links to the localized dashboard home, no token. */
    fun emailChangedNoticeEmail(locale: String): ComposedEmail = compose("emailChangedNotice", locale, "${properties.appBaseUrl}/$locale")

    private fun link(
        locale: String,
        page: String,
        rawToken: String,
    ): String = "${properties.appBaseUrl}/$locale/$page?token=$rawToken"

    private fun compose(
        keyPrefix: String,
        locale: String,
        link: String,
    ): ComposedEmail {
        val resolved = Locale.forLanguageTag(locale)
        return ComposedEmail(
            subject = messageSource.getMessage("$keyPrefix.subject", null, resolved),
            htmlBody = messageSource.getMessage("$keyPrefix.body", arrayOf(link), resolved),
        )
    }
}
