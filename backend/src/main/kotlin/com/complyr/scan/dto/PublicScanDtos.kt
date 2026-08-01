package com.complyr.scan.dto

import jakarta.validation.constraints.NotBlank

/**
 * Anonymous free-scan request from the marketing site. Only a raw domain — no auth, no owning site.
 * The value is loosely shaped here ([NotBlank]) and strictly normalized/validated by
 * `DomainValidator` in the service (which rejects IP literals, localhost, single labels, etc.).
 */
data class PublicScanRequest(
    @field:NotBlank
    val domain: String,
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
