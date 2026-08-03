package com.complyr.consent

import com.complyr.consent.dto.ConsentEventLogResponse
import com.complyr.consent.dto.ConsentLogFilter
import com.complyr.site.SiteNotFoundException
import com.complyr.site.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A page of consent-log rows plus the opaque cursor for the next (older) page, null when this is the last page. */
data class ConsentLogPage(
    val events: List<ConsentEventLogResponse>,
    val nextCursor: String?,
)

/**
 * Read side of the consent audit log for the dashboard. Every read is ownership-gated first
 * (`findByIdAndUserId`) so another user's site id is indistinguishable from a true miss — matching
 * [ScanQueryService][com.complyr.scan.ScanQueryService]. Paging is keyset (newest-first by UUIDv7 `eventId`):
 * we over-fetch one row to detect whether an older page exists, then hand back its cursor.
 */
@Service
class ConsentLogService(
    private val siteRepository: SiteRepository,
    private val consentEventRepository: ConsentEventRepository,
) {
    @Transactional(readOnly = true)
    fun list(
        userId: UUID,
        siteId: UUID,
        filter: ConsentLogFilter,
    ): ConsentLogPage {
        requireOwnedSite(userId, siteId)
        val pageSize = (filter.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val query =
            ConsentLogQuery(
                from = filter.from,
                to = filter.to,
                action = filter.action,
                lang = filter.lang,
                visitorId = filter.visitorId,
                cursor = filter.cursor?.let(ConsentLogCursor::decode),
                // Over-fetch by one: a full extra row means there's an older page to link to.
                limit = pageSize + 1,
            )

        val rows = consentEventRepository.search(siteId, query)
        val hasMore = rows.size > pageSize
        val page = if (hasMore) rows.take(pageSize) else rows
        val nextCursor = if (hasMore) encodeCursor(page.last()) else null

        return ConsentLogPage(events = page.map(ConsentEventLogResponse::from), nextCursor = nextCursor)
    }

    private fun encodeCursor(last: ConsentEventEntity): String =
        ConsentLogCursor.encode(
            ConsentLogCursorPosition(
                createdAt = last.createdAt,
                eventId = requireNotNull(last.eventId) { "persisted consent event must have an id" },
            ),
        )

    /**
     * Ownership gate on its own, for callers (CSV export) that must reject a foreign or unknown site with 404
     * *before* they start writing a streamed response body — once bytes flush, the status can no longer change.
     */
    @Transactional(readOnly = true)
    fun assertOwnership(
        userId: UUID,
        siteId: UUID,
    ) = requireOwnedSite(userId, siteId)

    private fun requireOwnedSite(
        userId: UUID,
        siteId: UUID,
    ) {
        siteRepository.findByIdAndUserId(siteId, userId) ?: throw SiteNotFoundException()
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
