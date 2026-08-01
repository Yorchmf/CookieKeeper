package com.complyr.scan

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Persists an anonymous scan's observed cookies in a single transaction — the `public_scan_cookies`
 * twin of [ScanCookieWriter]. The delete-then-insert replacement is atomic so a crash or a
 * lease-overrun re-claim can't leave a half-cleared set, and there's no self-invocation gap (the
 * crawler is a separate bean, so this `@Transactional` boundary actually applies). The crawl itself
 * holds no transaction; this is the only DB-transactional step.
 */
@Component
class PublicScanCookieWriter(
    private val repository: PublicScanCookieRepository,
) {
    /**
     * Replace [publicScanId]'s prior findings with [cookies] atomically. Public-scan findings are
     * replaceable (not append-only audit evidence like `consent_events`), so a re-run deletes then
     * re-inserts.
     */
    @Transactional
    fun replace(
        publicScanId: UUID,
        cookies: List<PublicScanCookieEntity>,
    ) {
        repository.deleteByPublicScanId(publicScanId)
        if (cookies.isNotEmpty()) {
            repository.saveAll(cookies)
        }
    }
}
