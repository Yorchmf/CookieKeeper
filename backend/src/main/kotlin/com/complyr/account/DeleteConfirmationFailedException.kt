package com.complyr.account

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The password confirming an account deletion did not match. 403 rather than 401: the session itself is
 * valid, it is the re-authentication that failed — a 401 would make the dashboard's interceptor log the
 * user out mid-flow and hide the real reason.
 */
class DeleteConfirmationFailedException :
    ApiException(
        HttpStatus.FORBIDDEN,
        code = "DELETE_CONFIRMATION_FAILED",
        message = "Password confirmation failed",
    )
