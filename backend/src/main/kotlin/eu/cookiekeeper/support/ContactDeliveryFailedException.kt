package eu.cookiekeeper.support

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The contact message could not be handed to the mail provider. Surfaced to the customer as a 503 so the
 * form can tell them honestly that it did not go through and let them retry — unlike best-effort
 * transactional mail, a support message the sender believes was delivered but was not is worse than an
 * error. The message is client-safe and carries no PII.
 */
class ContactDeliveryFailedException :
    ApiException(
        status = HttpStatus.SERVICE_UNAVAILABLE,
        code = "CONTACT_DELIVERY_FAILED",
        message = "Could not send your message right now. Please try again shortly.",
    )
