package com.complyr.consent.dto

import com.complyr.consent.ConsentEventEntity
import org.springframework.format.annotation.DateTimeFormat
import java.time.Instant
import java.util.UUID

/**
 * Inbound query filters for the consent-log read, bound from the request's query string as a command object
 * (keeps the controller signature small and the filter set in one place). All fields are optional; [from] is
 * inclusive and [to] exclusive, [visitorId] is an exact match, [cursor] is the opaque keyset token from a prior
 * page's `meta.nextCursor`, and [limit] is clamped to a sane page size by the service.
 */
data class ConsentLogFilter(
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val from: Instant? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val to: Instant? = null,
    val action: String? = null,
    val lang: String? = null,
    val visitorId: UUID? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

/**
 * One consent event as the dashboard audit log renders it. Deliberately excludes `ipHash` and the trimmed
 * user agent: neither is needed by the UI and both are minimal-PII surface we don't widen to the browser
 * (CLAUDE.md #4). `siteId` is implied by the path and omitted. [eventId] doubles as the row's stable key.
 */
data class ConsentEventLogResponse(
    val eventId: UUID,
    val visitorId: UUID,
    val action: String,
    val categories: Map<String, Boolean>,
    val bannerVersion: Int?,
    val policyVersion: Int?,
    val lang: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(event: ConsentEventEntity): ConsentEventLogResponse =
            ConsentEventLogResponse(
                eventId = requireNotNull(event.eventId) { "persisted consent event must have an id" },
                visitorId = event.visitorId,
                action = event.action,
                categories = event.categories,
                bannerVersion = event.bannerVersion,
                policyVersion = event.policyVersion,
                lang = event.lang,
                createdAt = event.createdAt,
            )
    }
}
