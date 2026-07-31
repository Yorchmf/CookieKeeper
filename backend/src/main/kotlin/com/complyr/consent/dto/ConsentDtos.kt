package com.complyr.consent.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Public consent-ingestion payload (POST /api/v1/consent). Unauthenticated and
 * cross-origin, so every field is treated as untrusted: sizes are bounded here and
 * values are re-validated in the service. The client-sent timestamp is intentionally
 * absent — the server stamps `created_at` so audit time can't be forged. [eventKey] is
 * a dedupe key only, never used as audit time.
 */
data class ConsentEventRequest(
    @field:NotBlank
    @field:Size(max = MAX_SITE_KEY_LENGTH)
    val siteKey: String,
    @field:NotBlank
    @field:Size(max = MAX_ACTION_LENGTH)
    val action: String,
    @field:NotNull
    @field:Size(max = MAX_CATEGORIES)
    val categories: Map<String, Boolean>,
    @field:Size(max = MAX_LANG_LENGTH)
    val lang: String? = null,
    /** Cookie-stored per-visitor UUID; the server mints one when absent or malformed. */
    @field:Size(max = MAX_VID_LENGTH)
    val vid: String? = null,
    val bannerVersion: Int? = null,
    val policyVersion: Int? = null,
    /**
     * Client-generated idempotency key (UUID), stable across the widget's retry replays, so a
     * double-delivered event is recorded once. Absent or malformed → no de-dupe, and the event
     * is still recorded (losing audit evidence is worse than a rare duplicate).
     */
    @field:Size(max = MAX_EVENT_KEY_LENGTH)
    val eventKey: String? = null,
) {
    companion object {
        const val MAX_SITE_KEY_LENGTH = 64
        const val MAX_ACTION_LENGTH = 16
        const val MAX_CATEGORIES = 20
        const val MAX_CATEGORY_KEY_LENGTH = 64
        const val MAX_LANG_LENGTH = 12
        const val MAX_VID_LENGTH = 36
        const val MAX_EVENT_KEY_LENGTH = 36
    }
}

/** Opaque acknowledgement — the widget is fire-and-forget and ignores the body. */
data class ConsentAcceptedResponse(
    val recorded: Boolean = true,
)
