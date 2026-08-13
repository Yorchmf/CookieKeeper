package com.complyr.account

import com.complyr.account.dto.AccountDeletionResponse
import com.complyr.account.dto.AccountExport
import com.complyr.account.dto.ChangePasswordRequest
import com.complyr.account.dto.DeleteAccountRequest
import com.complyr.account.dto.NotificationPreferencesResponse
import com.complyr.account.dto.RequestEmailChangeRequest
import com.complyr.account.dto.RevokeAllSessionsRequest
import com.complyr.account.dto.UpdateNotificationPreferencesRequest
import com.complyr.account.dto.UpdateProfileRequest
import com.complyr.auth.AuthCookieFactory
import com.complyr.auth.dto.OkResponse
import com.complyr.auth.dto.UserResponse
import com.complyr.common.ApiResponse
import com.complyr.common.CurrentUser
import com.complyr.notify.NotificationPreferenceService
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The customer's own GDPR rights over their Complyr account: Art. 20 portability and Art. 17 erasure
 * (ADR-20). JWT-authenticated and always scoped to the caller — there is no user id in any path here, so
 * neither route can be pointed at somebody else's account.
 *
 * We sell GDPR compliance; offering our customers less than we help them offer their visitors would be
 * indefensible, which is why these ship as first-class product surfaces rather than a support-ticket flow.
 */
@RestController
@RequestMapping("/api/v1/account")
class AccountController(
    private val exportService: AccountExportService,
    private val deletionService: AccountDeletionService,
    private val profileService: AccountProfileService,
    private val passwordService: AccountPasswordService,
    private val emailService: AccountEmailService,
    private val sessionService: AccountSessionService,
    private val notificationPreferenceService: NotificationPreferenceService,
    private val cookieFactory: AuthCookieFactory,
) {
    private companion object {
        /** Fetch-metadata header every current browser sends; see [requireSameSiteRequest]. */
        const val SEC_FETCH_SITE = "Sec-Fetch-Site"
        const val CROSS_SITE = "cross-site"
    }

    /**
     * The full account export as a downloadable JSON file. Deliberately NOT wrapped in the `{success,
     * data, …}` envelope: this is a file the customer keeps, and a portability artifact should not carry
     * our transport bookkeeping. `no-store` because the body is the account's entire personal record —
     * no shared cache or proxy should ever hold a copy.
     */
    @GetMapping("/export.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun export(
        @RequestHeader(name = SEC_FETCH_SITE, required = false) fetchSite: String?,
    ): ResponseEntity<AccountExport> {
        requireSameSiteRequest(fetchSite)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"complyr-account-export.json\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_JSON)
            .body(exportService.export(CurrentUser.id()))
    }

    /**
     * Rejects a cross-site *navigation* to the export.
     *
     * The session cookies are `SameSite=Lax`, which withholds them from cross-site subresource loads but
     * still sends them on a top-level GET navigation — so a link or `window.open` from an attacker's page
     * can make a logged-in customer's browser download their own export to disk. The attacker cannot read
     * the response (no CORS grant), so this is drive-by noise rather than a leak, but nothing legitimate
     * requests this file cross-site.
     *
     * Only an explicit `cross-site` is refused: a browser that omits `Sec-Fetch-Site` (or reports
     * `same-origin`/`same-site`/`none` for a direct address-bar hit) is allowed through, so the check can
     * never break the dashboard's own same-origin download, curl, or an older browser.
     */
    private fun requireSameSiteRequest(fetchSite: String?) {
        if (fetchSite.equals(CROSS_SITE, ignoreCase = true)) throw CrossSiteExportException()
    }

    /**
     * Updates the account holder's display name. PATCH because it is a partial update of the account
     * profile, scoped to the caller. Returns the refreshed user so the dashboard can update its identity
     * cache from the response rather than refetching `me`.
     */
    @PatchMapping("/profile")
    fun updateProfile(
        @Valid @RequestBody request: UpdateProfileRequest,
    ): ApiResponse<UserResponse> = ApiResponse.success(profileService.updateName(CurrentUser.id(), request.name))

    /**
     * Changes the account password after re-authenticating with the current one. POST, not PATCH: it is a
     * credential rotation with side effects (every session revoked), not a partial resource edit.
     *
     * The change revokes every refresh token, including this session's — so the cookies are cleared in the
     * same response and the dashboard sends the user back to sign in. Leaving them would strand a browser
     * whose refresh token is already dead, and re-issuing a session here would defeat the point of a
     * password change: invalidating anything an attacker might hold.
     */
    @PostMapping("/password")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<ApiResponse<OkResponse>> {
        passwordService.changePassword(CurrentUser.id(), request.currentPassword, request.newPassword)
        return clearedSession().body(ApiResponse.success(OkResponse()))
    }

    /**
     * Starts an email change (verify-new-first). POST, not PATCH: it does not edit the email resource yet —
     * it parks a pending address and mails a confirmation link to it. The session is deliberately NOT
     * cleared: nothing about the account has actually changed, so the current session stays valid until (and
     * after) the change is confirmed at `/auth/confirm-email-change`. Returns the refreshed user (login
     * email unchanged, `pendingEmail` now set) so the dashboard can render the "pending change to …" state
     * from this response instead of refetching `me`.
     */
    @PostMapping("/email")
    fun requestEmailChange(
        @Valid @RequestBody request: RequestEmailChangeRequest,
    ): ApiResponse<UserResponse> =
        ApiResponse.success(emailService.requestEmailChange(CurrentUser.id(), request.newEmail, request.currentPassword))

    /**
     * Signs the account out of every device. POST, not DELETE: it carries a body (the confirming password)
     * and revokes a set of sessions rather than deleting a resource. Re-authentication is required even
     * though the session is already authenticated, so an unattended logged-in browser cannot revoke the
     * account's other sessions with one click; a wrong password is refused before anything is revoked.
     *
     * Every refresh token is revoked, including this session's, so the cookies are cleared in the same
     * response and the dashboard returns the user to sign in — same shape as the password change. Access
     * JWTs already held by other devices are stateless and stay valid until they expire; only refresh tokens
     * die immediately, so those devices lose the ability to renew and drop out within the access-token TTL.
     */
    @PostMapping("/sessions/revoke-all")
    fun revokeAllSessions(
        @Valid @RequestBody request: RevokeAllSessionsRequest,
    ): ResponseEntity<ApiResponse<OkResponse>> {
        sessionService.revokeAllSessions(CurrentUser.id(), request.currentPassword)
        return clearedSession().body(ApiResponse.success(OkResponse()))
    }

    /**
     * The account's current email notification preferences. Returns the all-on default when the account
     * has never changed one (no row exists yet), so the dashboard always renders a concrete state. Scoped
     * to the caller — no user id on the path.
     */
    @GetMapping("/notifications")
    fun getNotificationPreferences(): ApiResponse<NotificationPreferencesResponse> =
        ApiResponse.success(NotificationPreferencesResponse.from(notificationPreferenceService.get(CurrentUser.id())))

    /**
     * Replaces the account's notification preferences (full PUT — both flags required, an omitted field is
     * a 400 rather than a silent opt-out). No re-authentication: toggling an email preference is low-stakes
     * and reversible, unlike a credential or session change. Returns the stored state so the dashboard can
     * confirm from the response without a re-read.
     */
    @PutMapping("/notifications")
    fun updateNotificationPreferences(
        @Valid @RequestBody request: UpdateNotificationPreferencesRequest,
    ): ApiResponse<NotificationPreferencesResponse> =
        ApiResponse.success(
            NotificationPreferencesResponse.from(
                notificationPreferenceService.update(CurrentUser.id(), request.toPreferences()),
            ),
        )

    /**
     * Erases the account (Art. 17). POST, not DELETE, because it carries a body: the confirming password.
     *
     * On success the session cookies are cleared in the same response — the erasure already destroyed the
     * refresh token server-side, and leaving a still-verifiable access JWT in the browser for the rest of
     * its TTL would let a deleted account keep making calls. The response body reports what actually
     * happened so the farewell screen can state it rather than imply it (see [AccountDeletionResponse]).
     */
    @PostMapping("/delete")
    fun delete(
        @Valid @RequestBody request: DeleteAccountRequest,
    ): ResponseEntity<ApiResponse<AccountDeletionResponse>> {
        val result = deletionService.delete(CurrentUser.id(), request.password)
        return clearedSession().body(ApiResponse.success(result))
    }

    /**
     * A 200 builder that expires all three session cookies. Both endpoints that end the session — erasure
     * and password change — clear the same trio, so the header set lives in one place.
     */
    private fun clearedSession(): ResponseEntity.BodyBuilder =
        ResponseEntity
            .ok()
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredAccessCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredRefreshCookie().toString())
            .header(HttpHeaders.SET_COOKIE, cookieFactory.expiredSessionMarkerCookie().toString())
}
