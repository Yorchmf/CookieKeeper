package eu.cookiekeeper.scan

import org.springframework.stereotype.Component

/**
 * Enriches a scan's observed cookies with a category/provider from the seeded signature DB (§13 W4).
 * A hit sets [ScanCookieEntity.category]/[ScanCookieEntity.provider] and flips `isKnown = true`; a miss
 * leaves the cookie as-is (category/provider null, `isKnown = false`) so the dashboard can surface it in
 * the "needs review" bucket the customer categorizes — those categorizations later enrich the DB.
 *
 * Signatures are loaded once per [classify] call (once per scan) rather than cached: the table is tiny
 * (curated seed) and scans are infrequent, so a per-scan read keeps this correct with zero staleness.
 */
@Component
class CookieClassifier(
    private val repository: CookieSignatureRepository,
) {
    /** Return copies of [cookies] with signature matches applied; unmatched cookies are unchanged. */
    fun classify(cookies: List<ScanCookieEntity>): List<ScanCookieEntity> {
        if (cookies.isEmpty()) return cookies
        val matcher = CookieSignatureMatcher(repository.findAll().map(CookieSignatureEntity::toSignature))
        return cookies.map { cookie -> enrich(matcher, cookie) }
    }

    private fun enrich(
        matcher: CookieSignatureMatcher,
        cookie: ScanCookieEntity,
    ): ScanCookieEntity {
        val hit = matcher.match(cookie.name) ?: return cookie
        return cookie.copy(category = hit.category, provider = hit.provider, isKnown = true)
    }
}
