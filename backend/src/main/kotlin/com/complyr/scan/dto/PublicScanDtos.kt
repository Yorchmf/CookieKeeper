package com.complyr.scan.dto

import jakarta.validation.constraints.NotBlank

/**
 * Anonymous free-scan request from the marketing site. Only a raw domain — no auth, no owning site.
 * The value is loosely shaped here ([NotBlank]) and strictly normalized/validated by
 * `DomainValidator` in the service (which rejects IP literals, localhost, single labels, etc.).
 *
 * [website] is a honeypot: a decoy field the visible form keeps hidden (off-screen, `aria-hidden`,
 * `autocomplete="off"`) so a human never fills it, while naive form-filling bots do. Any non-blank
 * value marks the request as automated — the service silently no-ops it (see
 * [com.complyr.scan.PublicScanService]) rather than 400ing, so the bot gets no signal that it was
 * detected. It is deliberately NOT bean-validated (a `@NotBlank`/`@Null` would leak the trap).
 */
data class PublicScanRequest(
    @field:NotBlank
    val domain: String,
    val website: String? = null,
)

/**
 * What the caller polls on after requesting a scan: the opaque [token] to read the result by and the
 * current [status] (`queued`/`running`/`done`/`failed`). A cache hit returns `done` immediately; a
 * fresh enqueue returns `queued`.
 */
data class PublicScanCreatedResponse(
    val token: String,
    val status: String,
)
