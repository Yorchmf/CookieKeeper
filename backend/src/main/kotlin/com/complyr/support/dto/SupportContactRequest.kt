package com.complyr.support.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * An in-app support message from an authenticated customer. The submitter is never carried in the body:
 * their identity (email for the Reply-To, locale for context) is resolved server-side from the JWT, so a
 * client cannot spoof who a message is from.
 *
 * [subject] and [message] are free text and flow into an HTML email to our support inbox — escaped at the
 * composition boundary ([com.complyr.support.ContactEmailComposer], via `HtmlText`). Neither is ever used
 * as the email's Subject header (that is a fixed string) so there is no header-injection sink. The size
 * caps bound both the email we send ourselves and the request body we accept.
 */
data class SupportContactRequest(
    @field:NotBlank
    @field:Size(max = MAX_SUBJECT_LENGTH)
    val subject: String,
    @field:NotBlank
    @field:Size(max = MAX_MESSAGE_LENGTH)
    val message: String,
) {
    companion object {
        const val MAX_SUBJECT_LENGTH = 150
        const val MAX_MESSAGE_LENGTH = 5_000
    }
}
