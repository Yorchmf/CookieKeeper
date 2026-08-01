package com.complyr.scan.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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

/**
 * The email gate that unlocks the detailed report ([PublicScanReportResponse]). The address is the
 * marketing lead — validated syntactically here and captured onto the scan row, never logged (PII,
 * CLAUDE.md #4). The lawful basis / consent copy for storing it lives in the funnel UI (slice F).
 */
data class PublicScanReportRequest(
    @field:NotBlank
    @field:Email
    // Bound attacker-controlled input at the boundary (RFC 5321 max) rather than at the raw DB length;
    // `@Email` is only a syntactic check. Downstream consumers (lead export, marketing email templates)
    // must still escape/neutralize this value — a stored address can carry CSV-formula or HTML payloads.
    @field:Size(max = 254)
    val email: String,
)
