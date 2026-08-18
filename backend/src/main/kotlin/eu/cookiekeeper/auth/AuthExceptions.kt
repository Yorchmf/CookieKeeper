package eu.cookiekeeper.auth

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus

class EmailAlreadyRegisteredException :
    ApiException(HttpStatus.CONFLICT, code = "EMAIL_IN_USE", message = "Email is already registered")

/** Identical response for unknown email and wrong password (anti-enumeration). */
class InvalidCredentialsException :
    ApiException(HttpStatus.UNAUTHORIZED, code = "INVALID_CREDENTIALS", message = "Invalid email or password")

/** Generic for expired, used, and unknown verification/reset tokens (anti-enumeration). */
class InvalidTokenException : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_TOKEN", message = "Token is invalid or expired")

class InvalidRefreshTokenException :
    ApiException(HttpStatus.UNAUTHORIZED, code = "INVALID_REFRESH_TOKEN", message = "Refresh token is invalid")

class EmailNotVerifiedException :
    ApiException(HttpStatus.FORBIDDEN, code = "EMAIL_NOT_VERIFIED", message = "Email address must be verified first")
