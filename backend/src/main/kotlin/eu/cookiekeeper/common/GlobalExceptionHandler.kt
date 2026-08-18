package eu.cookiekeeper.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Maps exceptions to the standard [ApiResponse] envelope.
 *
 * Error messages returned to clients are intentionally generic for unexpected failures;
 * full details are logged server-side only (no PII in logs).
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val message =
            ex.bindingResult.fieldErrors.joinToString(separator = "; ") { fieldError ->
                "${fieldError.field}: ${fieldError.defaultMessage ?: "invalid value"}"
            }
        return respond(
            HttpStatus.BAD_REQUEST,
            code = "VALIDATION_ERROR",
            message = message.ifBlank { "Invalid request" },
        )
    }

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ApiResponse<Nothing>> =
        respond(ex.status, code = ex.code, message = ex.message ?: "Request failed")

    // Note: no blanket IllegalArgumentException handler — raw IAE messages are internal detail
    // and must fall through to the generic 500 path. Client-facing 400s use typed ApiExceptions.
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(): ResponseEntity<ApiResponse<Nothing>> =
        respond(HttpStatus.BAD_REQUEST, code = "BAD_REQUEST", message = "Malformed request body")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiResponse<Nothing>> =
        respond(HttpStatus.BAD_REQUEST, code = "BAD_REQUEST", message = "Invalid value for parameter '${ex.name}'")

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(): ResponseEntity<ApiResponse<Nothing>> =
        respond(HttpStatus.NOT_FOUND, code = "NOT_FOUND", message = "Resource not found")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ApiResponse<Nothing>> =
        respond(
            HttpStatus.METHOD_NOT_ALLOWED,
            code = "METHOD_NOT_ALLOWED",
            message = "HTTP method '${ex.method}' is not supported for this endpoint",
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        // Spring Security must translate its own exceptions to 401/403 —
        // swallowing them here would turn auth failures into 500s.
        if (ex is AccessDeniedException || ex is AuthenticationException) throw ex
        log.error("Unhandled exception", ex)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR,
            code = "INTERNAL_ERROR",
            message = "An unexpected error occurred",
        )
    }

    private fun respond(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ApiResponse<Nothing>> = ResponseEntity.status(status).body(ApiResponse.error(code, message))
}
