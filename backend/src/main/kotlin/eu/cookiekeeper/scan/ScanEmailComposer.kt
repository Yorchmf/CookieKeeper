package eu.cookiekeeper.scan

import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.HtmlText
import eu.cookiekeeper.notify.ComposedEmail
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
 * Everything the "still not blocking" nudge renders (BACKLOG #19). [vendorNames] are display names from
 * our own curated tracker dataset, never observed hosts (§4), and [daysUnresolved] is how long the site
 * has been making a promise it does not keep — the one number that makes this email worth opening.
 */
data class BlockingAlertSummary(
    val siteId: UUID,
    val scanId: UUID,
    val domain: String,
    val daysUnresolved: Long,
    val vendorNames: List<String>,
    val wrongSiteKey: Boolean,
)

/**
 * The localized scan-complete email, from the same `messages/notify_*` bundles (EN/DE/FR/ES/IT) the
 * auth and billing emails use. The link points at the scan's report page in the dashboard
 * (`cookiekeeper.app-base-url`), which is where the user acts on the result: review each cookie, adjust
 * the banner categories, regenerate the policy.
 */
@Service
class ScanEmailComposer(
    private val messageSource: MessageSource,
    private val properties: CookieKeeperProperties,
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
                        // Defence in depth: domains are normalized and validated before a site is stored, but an
                        // email body is HTML rendered into someone's mail client, so a validator regression must
                        // not turn into markup there. Counts are ints; the link is our own base URL plus UUIDs.
                        HtmlText.escape(summary.domain),
                        summary.cookieCount.toString(),
                        summary.marketingTrackerCount.toString(),
                        link,
                    ),
                    resolved,
                ),
        )
    }

    /**
     * The nudge for a site whose widget is installed but not doing its job. Two message sets rather than
     * one with an optional list: "you never tagged these scripts" and "this page is embedding a different
     * site's key" are different problems with different fixes, and a single generic body would be vague
     * about both.
     */
    fun blockingAlertEmail(
        locale: String,
        summary: BlockingAlertSummary,
    ): ComposedEmail {
        val resolved = Locale.forLanguageTag(locale)
        val link = "${properties.appBaseUrl}/$locale/sites/${summary.siteId}/scans/${summary.scanId}"
        val prefix = if (summary.wrongSiteKey) "blockingAlertSiteKey" else "blockingAlertUnblocked"
        val args =
            buildList {
                // Same defence in depth as above: the dataset names and the domain are ours, but an email
                // body is HTML in someone's mail client and must not depend on that staying true.
                add(HtmlText.escape(summary.domain))
                add(summary.daysUnresolved.toString())
                if (!summary.wrongSiteKey) {
                    add(HtmlText.escape(summary.vendorNames.take(MAX_LISTED_VENDORS).joinToString(", ")))
                }
                add(link)
            }.toTypedArray()
        return ComposedEmail(
            subject = messageSource.getMessage("$prefix.subject", arrayOf(summary.domain), resolved),
            htmlBody = messageSource.getMessage("$prefix.body", args, resolved),
        )
    }

    private companion object {
        /** Enough to make the problem concrete without turning the email into a report. */
        const val MAX_LISTED_VENDORS = 5
    }
}
