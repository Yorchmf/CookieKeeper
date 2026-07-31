package com.complyr.scan

/**
 * One entry from the signature DB, decoupled from JPA so [CookieSignatureMatcher] is unit-testable
 * without a database. [namePattern] is the full cookie name when [isWildcard] is false, or the stable
 * prefix of a cookie family (e.g. GA4's `_ga_`) when true. [category] is a canonical taxonomy key.
 */
data class CookieSignature(
    val namePattern: String,
    val isWildcard: Boolean,
    val provider: String,
    val category: String,
)

/** The classification a signature yields for a cookie: which vendor set it and under which category. */
data class CookieClassification(
    val category: String,
    val provider: String,
)

/**
 * Matches an observed cookie name against the seeded signature DB. Precedence: an exact name match
 * always wins; failing that, the longest [isWildcard] prefix the name starts with; failing that, no
 * match (the caller flags the cookie "needs review"). Pure and allocation-light — the maps/list are
 * built once per scan from [CookieSignatureRepository.findAll], then reused for every cookie.
 *
 * Matching is a plain `startsWith`, never a regex, so an attacker-chosen cookie name from a crawled
 * page cannot trigger catastrophic backtracking (ReDoS).
 */
class CookieSignatureMatcher(
    signatures: List<CookieSignature>,
) {
    private val exact: Map<String, CookieSignature> =
        signatures.filterNot { it.isWildcard }.associateBy { it.namePattern }

    // Longest prefix first so the most specific family (e.g. `_gat_` before a hypothetical `_ga`) wins.
    // An empty prefix is dropped: startsWith("") matches every cookie, so one bad seed row would
    // otherwise mislabel an entire scan. The migration also CHECK-guards this; belt and braces.
    private val wildcards: List<CookieSignature> =
        signatures
            .filter { it.isWildcard && it.namePattern.isNotEmpty() }
            .sortedByDescending { it.namePattern.length }

    /** Classify [cookieName], or null when nothing in the signature DB matches it. */
    fun match(cookieName: String): CookieClassification? {
        val exactHit = exact[cookieName]
        if (exactHit != null) return CookieClassification(exactHit.category, exactHit.provider)
        val wildcardHit = wildcards.firstOrNull { cookieName.startsWith(it.namePattern) }
        return wildcardHit?.let { CookieClassification(it.category, it.provider) }
    }
}
