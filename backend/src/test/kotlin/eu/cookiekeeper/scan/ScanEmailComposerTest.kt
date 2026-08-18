package eu.cookiekeeper.scan

import eu.cookiekeeper.common.CookieKeeperProperties
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders the real `messages/notify_*` bundles through the scan composer. Resolving every locale
 * doubles as a parity check: a missing `scanCompleted.*` key (or a stray un-doubled apostrophe, which
 * MessageFormat would choke on — the FR and IT strings both contain one) surfaces here.
 */
class ScanEmailComposerTest {
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

    private val composer = ScanEmailComposer(messageSource, properties)

    private val siteId: UUID = UUID.randomUUID()
    private val scanId: UUID = UUID.randomUUID()

    private fun summary(
        domain: String = "shop.example.com",
        cookieCount: Int = 12,
        marketingTrackerCount: Int = 3,
    ) = ScanSummary(
        siteId = siteId,
        scanId = scanId,
        domain = domain,
        cookieCount = cookieCount,
        marketingTrackerCount = marketingTrackerCount,
    )

    @Test
    fun `links to the scan report page for the recipient's locale`() {
        val email = composer.scanCompletedEmail("en", summary())

        assertTrue(
            email.htmlBody.contains("https://app.cookiekeeper.eu/en/sites/$siteId/scans/$scanId"),
            "body must deep-link to this scan's report page: ${email.htmlBody}",
        )
    }

    @Test
    fun `names the domain and reports both counts`() {
        val email = composer.scanCompletedEmail("en", summary(cookieCount = 12, marketingTrackerCount = 3))

        assertTrue(email.subject.contains("shop.example.com"), "subject should name the site: ${email.subject}")
        assertTrue(email.htmlBody.contains("12"), "body should report the cookie count: ${email.htmlBody}")
        assertTrue(email.htmlBody.contains("3"), "body should report the tracker count: ${email.htmlBody}")
    }

    /**
     * Domains are validated before a site is stored, so this is defence in depth — but the body is HTML
     * rendered into someone's mail client, and a validator regression must not become markup there.
     */
    @Test
    fun `escapes the domain in the HTML body`() {
        val email = composer.scanCompletedEmail("en", summary(domain = "<script>alert(1)</script>"))

        assertFalse(email.htmlBody.contains("<script>"), "raw markup must not survive into the body")
        assertTrue(email.htmlBody.contains("&lt;script&gt;"), "the domain should appear escaped: ${email.htmlBody}")
    }

    /**
     * IDN domains are the reason this doesn't use `HtmlUtils.htmlEscape`: that would turn every non-ASCII
     * character into a numeric entity, so a German or Greek domain would arrive unreadable in the subject.
     */
    @Test
    fun `leaves non-ASCII domains intact`() {
        val email = composer.scanCompletedEmail("de", summary(domain = "münchen-café.de"))

        assertTrue(email.subject.contains("münchen-café.de"), "subject keeps the IDN verbatim: ${email.subject}")
        assertTrue(email.htmlBody.contains("münchen-café.de"), "body keeps the IDN readable: ${email.htmlBody}")
    }

    @Test
    fun `the scan-complete email resolves for every supported locale`() {
        listOf("en", "de", "fr", "es", "it").forEach { locale ->
            val email = composer.scanCompletedEmail(locale, summary())

            assertTrue(email.subject.isNotBlank(), "scanCompleted.subject missing for locale=$locale")
            assertTrue(email.subject.contains("shop.example.com"), "domain missing from subject for locale=$locale")
            assertTrue(
                email.htmlBody.contains("/$locale/sites/$siteId/scans/$scanId"),
                "report link missing for locale=$locale: ${email.htmlBody}",
            )
        }
    }
}
