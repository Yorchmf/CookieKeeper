package com.complyr.scan

import com.complyr.common.ComplyrProperties
import com.complyr.notify.ComposedEmail
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import java.util.Locale
import java.util.UUID

/**
 * Everything the scan-complete email renders, resolved by [ScanCompletionNotifier] before composing.
 * A value object rather than a parameter list so the composer stays a pure formatting step with no
 * repository reach-back of its own.
 */
data class ScanSummary(
    val siteId: UUID,
    val scanId: UUID,
    val domain: String,
    val cookieCount: Int,
    val marketingTrackerCount: Int,
)

/**
 * The localized scan-complete email, from the same `messages/notify_*` bundles (EN/DE/FR/ES/IT) the
 * auth and billing emails use. The link points at the scan's report page in the dashboard
 * (`complyr.app-base-url`), which is where the user acts on the result: review each cookie, adjust
 * the banner categories, regenerate the policy.
 */
@Service
class ScanEmailComposer(
    private val messageSource: MessageSource,
    private val properties: ComplyrProperties,
) {
    fun scanCompletedEmail(
        locale: String,
        summary: ScanSummary,
    ): ComposedEmail {
        val resolved = Locale.forLanguageTag(locale)
        val link = "${properties.appBaseUrl}/$locale/sites/${summary.siteId}/scans/${summary.scanId}"
        return ComposedEmail(
            // The subject is plain text, so it takes the domain verbatim; only the HTML body escapes it.
            subject = messageSource.getMessage("scanCompleted.subject", arrayOf(summary.domain), resolved),
            htmlBody =
                messageSource.getMessage(
                    "scanCompleted.body",
                    arrayOf(
                        escapeHtml(summary.domain),
                        summary.cookieCount.toString(),
                        summary.marketingTrackerCount.toString(),
                        link,
                    ),
                    resolved,
                ),
        )
    }

    /**
     * Escape the one interpolated value that originates from customer input. Domains are normalized and
     * validated before a site is ever stored, so this is defence in depth rather than a live fix — but an
     * email body is HTML we render into someone's mail client, and a validator regression must not turn
     * into markup there. Counts are ints and the link is built from our own base URL plus UUIDs.
     *
     * Deliberately not `HtmlUtils.htmlEscape`: that also escapes non-ASCII to numeric entities, which
     * would mangle the IDN domains this product exists to serve.
     */
    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
