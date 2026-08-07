package com.complyr.scan

/**
 * One entry in the bundled third-party tracker dataset (`resources/trackers/trackers.json`): a request
 * [domain] (the dictionary key) mapped to a human [name] and a [category] (`analytics` | `marketing` |
 * `necessary`). Ported from the reference scanner's `trackers.json`; the count feature only consumes the
 * `marketing` rows, but the full set is kept so the matcher's root/suffix fallbacks behave identically.
 */
data class TrackerSignature(
    val domain: String,
    val name: String,
    val category: String,
)

/**
 * Pure, browser-free host → tracker matching, extracted so the (SSRF-adjacent) normalization and
 * fallback rules are unit-testable without a crawl. Mirrors the reference scanner's `matchTracker`
 * exactly so our detection lines up with the numbers customers saw there:
 *  1. normalize the observed host (strip a leading `.`, strip a leading `www.`, lower-case),
 *  2. exact match on the dataset,
 *  3. root-domain fallback (last two labels — `region1.doubleclick.net` → `doubleclick.net`),
 *  4. suffix scan — the host equals a dataset key or ends with `.<key>`.
 *
 * The dataset keys are trusted, curated data; the *host* is attacker-influenced (it comes from a crawled
 * page's requests), so nothing here interprets it beyond string normalization — no URL parse, no lookup
 * by anything but a lower-cased label string.
 */
class TrackerSignatureMatcher(
    signatures: List<TrackerSignature>,
) {
    private val byDomain: Map<String, TrackerSignature> =
        signatures.associateBy { it.domain.lowercase() }

    /** The matching signature for [host], or null if the host is not a known tracker. */
    fun match(host: String): TrackerSignature? {
        val normalized = normalize(host)
        if (normalized.isEmpty()) return null
        return byDomain[normalized]
            ?: byDomain[rootDomain(normalized)]
            ?: byDomain.values.firstOrNull { normalized == it.domain || normalized.endsWith(".${it.domain}") }
    }

    /** Lower-case and strip a single leading `.` then a single leading `www.` (mirrors the reference). */
    private fun normalize(host: String): String {
        val lower = host.lowercase().removePrefix(".")
        return lower.removePrefix("www.")
    }

    /** The registrable-ish root: the last two labels, or the host itself when it has two or fewer. */
    private fun rootDomain(host: String): String {
        val parts = host.split(".")
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }
}
