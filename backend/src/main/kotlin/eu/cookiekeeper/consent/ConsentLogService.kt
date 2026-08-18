package eu.cookiekeeper.consent

import eu.cookiekeeper.billing.EntitlementService
import eu.cookiekeeper.consent.dto.ConsentEventLogResponse
import eu.cookiekeeper.consent.dto.ConsentLogFilter
import eu.cookiekeeper.site.SiteNotFoundException
import eu.cookiekeeper.site.SiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A page of consent-log rows plus the opaque cursor for the next (older) page, null when this is the last page. */
data class ConsentLogPage(
    val events: List<ConsentEventLogResponse>,
    val nextCursor: String?,
)

/**
 * Read side of the consent audit log for the dashboard. Every read is ownership-gated first
 * (`findByIdAndUserId`) so another user's site id is indistinguishable from a true miss — matching
 * [ScanQueryService][eu.cookiekeeper.scan.ScanQueryService]. Paging is keyset (newest-first by UUIDv7 `eventId`):
 * we over-fetch one row to detect whether an older page exists, then hand back its cursor.
 *
 * Reads are additionally floored at the account's plan retention window
 * ([EntitlementService.consentRetentionFloor]) — the read-layer half of retention that ADR-16 defines but the
 * tenant-blind partition reaper cannot deliver. The CSV export reuses [list], so it inherits the same floor.
 */
@Service
class ConsentLogService(
    private val siteRepository: SiteRepository,
    private val consentEventRepository: ConsentEventRepository,
    private val entitlementService: EntitlementService,
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
                from = retentionFloored(userId, filter.from),
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

    /**
     * Raise the caller's `from` to the plan's retention floor, so a request for older history simply returns
     * fewer rows rather than failing. A caller asking for a *narrower* window than the floor keeps their own
     * `from` — the floor is a lower bound on visible history, not a replacement for the filter.
     */
    private fun retentionFloored(
        userId: UUID,
        from: Instant?,
    ): Instant {
        val floor = entitlementService.consentRetentionFloor(userId)
        return if (from == null || from.isBefore(floor)) floor else from
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
