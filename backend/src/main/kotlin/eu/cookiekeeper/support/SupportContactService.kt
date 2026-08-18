package eu.cookiekeeper.support

import eu.cookiekeeper.auth.UserRepository
import eu.cookiekeeper.common.CookieKeeperProperties
import eu.cookiekeeper.common.UnauthenticatedException
import eu.cookiekeeper.notify.EmailDeliveryException
import eu.cookiekeeper.notify.EmailSender
import eu.cookiekeeper.support.dto.SupportContactRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Sends an in-app contact-form submission to our support inbox.
 *
 * Unlike the transactional notifiers, delivery here is NOT best-effort: the customer is waiting on the
 * result, so a provider failure propagates as [ContactDeliveryFailedException] (503) rather than being
 * swallowed — the form then tells them it did not send. The submitter's account address becomes the
 * email's Reply-To so a support agent can answer with a plain reply; it is a stored, verified address,
 * so it is safe as a Reply-To header and never appears in a log line (CLAUDE.md #4).
 */
@Service
class SupportContactService(
    private val userRepository: UserRepository,
    private val emailSender: EmailSender,
    private val composer: ContactEmailComposer,
    private val properties: CookieKeeperProperties,
) {
    private val log = LoggerFactory.getLogger(SupportContactService::class.java)

    fun submit(
        userId: UUID,
        request: SupportContactRequest,
    ) {
        // Erased (Art. 17) accounts are already rejected upstream by ErasedAccountFilter; an absent row
        // here is an access token whose user no longer exists, mapped to 401 like the other authed
        // services (AccountProfileService).
        val user = userRepository.findById(userId).orElseThrow { UnauthenticatedException() }
        val composed = composer.compose(user.email, user.locale, request.subject, request.message)
        try {
            emailSender.send(
                to = properties.supportInbox,
                subject = composed.subject,
                htmlBody = composed.htmlBody,
                replyTo = user.email,
            )
        } catch (ex: EmailDeliveryException) {
            // PII-safe: EmailSender curates a recipient-free message and chains no PII-bearing cause.
            log.warn("Support contact delivery failed for user {}", userId, ex)
            throw ContactDeliveryFailedException()
        }
    }
}
