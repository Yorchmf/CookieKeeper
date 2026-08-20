package eu.cookiekeeper.scan

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Matches the third-party hosts a crawl observed against the bundled signature dataset
 * (`resources/trackers/trackers.json`), and answers the two questions built on it: how many distinct
 * **marketing** trackers fired, and **which** consent-decidable vendors did (BACKLOG #19). The first is the backing signal for the
 * `third_party_trackers` compliance finding: our before-consent crawl already sees every request a page
 * fires, so a request to an ad network (with no consent given) is a pre-consent tracking violation the
 * cookie-only checks would miss entirely.
 *
 * **Nothing observed is persisted** (§4): the hosts are attacker-influenced, so only the resulting
 * integer and the matched dataset KEYS — curated values we shipped ourselves — leave this class.
 * Distinctness is by matched *signature*, not by observed host, so a network sharded across
 * `a.doubleclick.net` / `b.doubleclick.net` counts once (one vendor), which is the honest,
 * non-inflatable number to show a visitor.
 *
 * The dataset is loaded once at construction (like [CookieClassifier]'s per-scan read, but this set is
 * static so a single load suffices); a missing/malformed resource fails fast at startup rather than
 * silently scoring every scan as tracker-free.
 */
@Component
class TrackerClassifier(
    objectMapper: ObjectMapper,
) {
    private val matcher: TrackerSignatureMatcher = TrackerSignatureMatcher(load(objectMapper))

    /**
     * How many distinct marketing trackers [hosts] contains. [hosts] are the crawl's observed
     * third-party request hosts; unmatched or non-marketing hosts are ignored.
     */
    fun countMarketingTrackers(hosts: Set<String>): Int = identify(hosts).count { it.category == TrackerSignature.MARKETING_CATEGORY }

    /**
     * The distinct **consent-decidable** vendors [hosts] contains, sorted by dataset key — the backing
     * signal for the post-install blocking verification (BACKLOG #19). Analytics is included here where
     * [countMarketingTrackers] excludes it: Google Analytics firing before consent is the single most
     * common way a site with a banner is still non-compliant, and it is exactly what the customer needs
     * named. Vendors we classify as `necessary` are left out — we never tell someone to block those.
     *
     * The returned values are dataset rows, not observed hosts: the caller may persist their [domain]
     * keys and show their [name]s (§4 — nothing attacker-controlled leaves the crawl).
     */
    fun identifyDecidable(hosts: Set<String>): List<TrackerSignature> = identify(hosts).filter { it.consentCategoryKey() != null }

    /** Resolve stored dataset keys back to rows, dropping any key a later dataset revision removed. */
    fun describe(domains: List<String>): List<TrackerSignature> = domains.mapNotNull(matcher::byKey)

    /**
     * The distinct dataset rows [hosts] matched, sorted by key. Distinctness is by matched *signature*,
     * never by observed host, so a network sharded across `a.doubleclick.net` / `b.doubleclick.net`
     * counts once.
     */
    private fun identify(hosts: Set<String>): List<TrackerSignature> =
        hosts
            .mapNotNull(matcher::match)
            .distinctBy { it.domain }
            .sortedBy { it.domain }

    private fun load(objectMapper: ObjectMapper): List<TrackerSignature> {
        val mapType =
            objectMapper.typeFactory.constructMapType(
                LinkedHashMap::class.java,
                String::class.java,
                TrackerEntry::class.java,
            )
        val raw: Map<String, TrackerEntry> =
            ClassPathResource(DATASET_PATH).inputStream.use { input ->
                objectMapper.readValue(input, mapType)
            }
        return raw.map { (domain, entry) ->
            TrackerSignature(domain = domain, name = entry.name, category = entry.category)
        }
    }

    /** Jackson binding for one dataset row (`{ "name": …, "category": … }`); the key is the domain. */
    private data class TrackerEntry(
        val name: String = "",
        val category: String = "",
    )

    private companion object {
        const val DATASET_PATH = "trackers/trackers.json"
    }
}
