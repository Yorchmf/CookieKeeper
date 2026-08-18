package eu.cookiekeeper.common

import org.springframework.http.HttpStatus

/**
 * Base class for typed business exceptions carrying their HTTP status and envelope error code.
 * Mapped centrally in [GlobalExceptionHandler]. Messages must never contain PII.
 */
abstract class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message)

/** Thrown when the security principal is missing or malformed. */
class UnauthenticatedException : ApiException(HttpStatus.UNAUTHORIZED, code = "UNAUTHENTICATED", message = "Authentication required")

/** Thrown by controllers for unparseable query parameters — the message must stay client-safe. */
class InvalidQueryParamException(
    message: String,
) : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_QUERY_PARAM", message = message)
