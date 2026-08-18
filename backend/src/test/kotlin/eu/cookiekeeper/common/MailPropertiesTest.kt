package eu.cookiekeeper.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * The mail provider is always materialised (application.yml defaults it), so a typo would match neither
 * sender's case-sensitive `@ConditionalOnProperty` and leave no `EmailSender` bean — surfacing as an
 * opaque `NoSuchBeanDefinitionException` far from the cause. These lock the fail-fast guard.
 */
class MailPropertiesTest {
    @Test
    fun `accepts the supported providers`() {
        assertEquals("smtp", CookieKeeperProperties.Mail(provider = "smtp").provider)
        assertEquals("brevo", CookieKeeperProperties.Mail(provider = "brevo").provider)
    }

    @Test
    fun `defaults to smtp`() {
        assertEquals("smtp", CookieKeeperProperties.Mail().provider)
    }

    @Test
    fun `rejects an unknown or mis-cased provider`() {
        assertThrows<IllegalArgumentException> { CookieKeeperProperties.Mail(provider = "sendgrid") }
        assertThrows<IllegalArgumentException> { CookieKeeperProperties.Mail(provider = "Brevo") }
    }
}
