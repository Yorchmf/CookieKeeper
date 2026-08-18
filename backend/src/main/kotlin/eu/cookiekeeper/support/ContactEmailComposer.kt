package eu.cookiekeeper.support

import eu.cookiekeeper.common.HtmlText
import eu.cookiekeeper.notify.ComposedEmail
import org.springframework.stereotype.Service

/**
 * Builds the internal support-inbox email for an in-app contact submission.
 *
 * Deliberately NOT localized through the `messages/notify_*` bundles the customer-facing emails use: this
 * message is read by our own support team, not the customer, so it is composed once in English. The
 * customer never sees it — their confirmation is the dashboard toast (i18n'd in the dashboard catalogs).
 * Keeping it out of the five locale bundles avoids five copies of an always-English internal string.
 *
 * Every customer-supplied value ([subject], [message], and the account [email] echoed for context) is
 * escaped with [HtmlText] before it enters the HTML body — the body is rendered in our support agent's
 * mail client, so a `<` in a message must stay text, not markup. The message's newlines become `<br>`
 * only after escaping, so a user cannot smuggle a tag through a line break.
 */
@Service
class ContactEmailComposer {
    fun compose(
        email: String,
        locale: String,
        subject: String,
        message: String,
    ): ComposedEmail =
        ComposedEmail(
            // Fixed Subject header (no user input) so there is no subject-header injection sink; the
            // customer's own subject line lives in the body below.
            subject = FIXED_SUBJECT,
            htmlBody =
                buildString {
                    append("<p><strong>New support message from a CookieKeeper customer.</strong></p>")
                    append("<p>From: ${HtmlText.escape(email)}<br>")
                    append("Account language: ${HtmlText.escape(locale)}</p>")
                    append("<p>Subject: ${HtmlText.escape(subject)}</p>")
                    append("<p>Message:</p>")
                    append("<p>${HtmlText.escape(message).replace("\n", "<br>")}</p>")
                    append("<hr><p>Reply directly to this email to answer the customer.</p>")
                },
        )

    private companion object {
        const val FIXED_SUBJECT = "New CookieKeeper contact-form message"
    }
}
