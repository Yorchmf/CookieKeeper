package eu.cookiekeeper.account

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The current password confirming a password change did not match. 403 rather than 401 for the same reason
 * as [DeleteConfirmationFailedException]: the session itself is valid, it is the re-authentication that
 * failed — a 401 would make the dashboard's interceptor log the user out mid-flow and hide the real reason.
 *
 * A distinct code from [DeleteConfirmationFailedException] so the change-password form can name the field
 * that was wrong ("current password") rather than borrow deletion's copy.
 */
class CurrentPasswordIncorrectException :
    ApiException(
        HttpStatus.FORBIDDEN,
        code = "CURRENT_PASSWORD_INCORRECT",
        message = "Password confirmation failed",
    )
