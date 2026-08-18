package com.complyr.consent.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Public banner-impression beacon payload (POST /api/v1/impression, Track 4 Slice D). Unauthenticated and
 * cross-origin like the consent post, so the one field is treated as untrusted: bounded here and re-validated
 * (site key → ACTIVE site) in the service.
 *
 * Intentionally minimal — only the site key. The impression counter's grain is (site_id, day, count), so the
 * beacon carries no visitor id, language, banner version, timestamp, or origin token: there is nothing else to
 * store, and adding fields would only invite personal data onto a pre-consent, PII-free path (CLAUDE.md #4).
 * The server stamps the UTC day; the client sends no time.
 */
data class ImpressionRequest(
    @field:NotBlank
    @field:Size(max = MAX_SITE_KEY_LENGTH)
    val siteKey: String,
) {
    companion object {
        // Matches ConsentEventRequest.MAX_SITE_KEY_LENGTH — the same site keys drive both endpoints.
        const val MAX_SITE_KEY_LENGTH = 64
    }
}

/** Opaque acknowledgement — the widget fires the beacon fire-and-forget and ignores the body. */
data class ImpressionAcceptedResponse(
    val recorded: Boolean = true,
)
