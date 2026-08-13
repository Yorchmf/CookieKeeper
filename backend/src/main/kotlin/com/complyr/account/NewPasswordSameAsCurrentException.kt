package com.complyr.account

import com.complyr.common.ApiException
import org.springframework.http.HttpStatus

/**
 * The new password submitted to a password change is identical to the current one. 400: the request is
 * well-formed and authenticated but asks for a no-op that would still revoke every session — refusing it
 * keeps "change password" meaning what it says.
 */
class NewPasswordSameAsCurrentException :
    ApiException(
        HttpStatus.BAD_REQUEST,
        code = "NEW_PASSWORD_SAME_AS_CURRENT",
        message = "The new password must be different from your current password",
    )
