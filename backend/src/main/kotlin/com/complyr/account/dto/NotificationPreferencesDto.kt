package com.complyr.account.dto

import com.complyr.notify.NotificationPreferences
import jakarta.validation.constraints.NotNull

/**
 * The account's email notification preferences as returned by `GET /api/v1/account/notifications` and
 * echoed back from the `PUT`. A flat boolean per customer-facing choice; the trigger plumbing behind
 * each flag ([com.complyr.scan.ScanCompletionNotifier]) is not exposed.
 */
data class NotificationPreferencesResponse(
    val scanComplete: Boolean,
    val scanChanges: Boolean,
) {
    companion object {
        fun from(preferences: NotificationPreferences): NotificationPreferencesResponse =
            NotificationPreferencesResponse(
                scanComplete = preferences.scanComplete,
                scanChanges = preferences.scanChanges,
            )
    }
}

/**
 * A full replacement of the account's notification preferences (`PUT /api/v1/account/notifications`).
 *
 * Both flags are `@NotNull` and nullable-typed on purpose: a PUT is a full replacement, and an omitted
 * field must be a 400, never a silent `false`. If they were primitive `Boolean`, Jackson would default a
 * missing key to `false` and a partial body would quietly opt the customer OUT of a notification they
 * never touched. The service reads them only after validation has rejected any null.
 */
data class UpdateNotificationPreferencesRequest(
    @field:NotNull
    val scanComplete: Boolean?,
    @field:NotNull
    val scanChanges: Boolean?,
) {
    /** Safe only after bean validation has passed — both fields are guaranteed non-null by then. */
    fun toPreferences(): NotificationPreferences =
        NotificationPreferences(
            scanComplete = requireNotNull(scanComplete),
            scanChanges = requireNotNull(scanChanges),
        )
}
