package eu.cookiekeeper.support

import eu.cookiekeeper.auth.dto.OkResponse
import eu.cookiekeeper.common.ApiResponse
import eu.cookiekeeper.common.CurrentUser
import eu.cookiekeeper.support.dto.SupportContactRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * In-app customer support. JWT-authenticated and always scoped to the caller — there is no user id on the
 * path, and the submitter's identity for the Reply-To is taken from the token, never the body, so a
 * message cannot be forged as coming from another account.
 *
 * `POST /api/v1/support/contact` is throttled per user by
 * [eu.cookiekeeper.common.AuthenticatedRateLimitFilter]'s tight CONTACT tier because each accepted call sends
 * an email to our inbox. Replaces the in-app `mailto:` link (which had no backing form) with a real
 * posting form so a customer can reach us without leaving the dashboard.
 */
@RestController
@RequestMapping("/api/v1/support")
class SupportController(
    private val supportContactService: SupportContactService,
) {
    @PostMapping("/contact")
    fun contact(
        @Valid @RequestBody request: SupportContactRequest,
    ): ApiResponse<OkResponse> {
        supportContactService.submit(CurrentUser.id(), request)
        return ApiResponse.success(OkResponse())
    }
}
