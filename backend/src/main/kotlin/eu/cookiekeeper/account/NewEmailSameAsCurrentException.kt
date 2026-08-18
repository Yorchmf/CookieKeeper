package eu.cookiekeeper.account

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The requested new email is the account's current address. 400 rather than silently succeeding: parking a
 * pending change to the address already in use, mailing a confirmation, and swapping nothing would be a
 * confusing no-op. Mirrors [eu.cookiekeeper.account.dto.ChangePasswordRequest]'s "new must differ" rule — the
 * check lives in the service because it needs the account's current email, not the request alone.
 */
class NewEmailSameAsCurrentException :
    ApiException(
        HttpStatus.BAD_REQUEST,
        code = "NEW_EMAIL_SAME_AS_CURRENT",
        message = "New email must differ from the current one",
    )
