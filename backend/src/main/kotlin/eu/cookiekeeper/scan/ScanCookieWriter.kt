package eu.cookiekeeper.scan

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Persists a scan's observed cookies in a single transaction. Extracted from [PlaywrightScanCrawler]
 * so the delete-then-insert replacement is atomic — a crash or a lease-overrun re-claim can't leave a
 * half-cleared set, and there's no self-invocation gap (the crawler is a separate bean, so this
 * `@Transactional` boundary actually applies). The crawl itself holds no transaction; this is the
 * only DB-transactional step.
 */
@Component
class ScanCookieWriter(
    private val repository: ScanCookieRepository,
) {
    /**
     * Replace [scanId]'s prior findings with [cookies] atomically. Scan findings are replaceable (not
     * append-only audit evidence like `consent_events`), so a re-run deletes then re-inserts.
     */
    @Transactional
    fun replace(
        scanId: UUID,
        cookies: List<ScanCookieEntity>,
    ) {
        repository.deleteByScanId(scanId)
        if (cookies.isNotEmpty()) {
            repository.saveAll(cookies)
        }
    }
}
