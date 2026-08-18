package eu.cookiekeeper.scan

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Counts the distinct **marketing** third-party trackers a crawl observed, against the bundled
 * signature dataset (`resources/trackers/trackers.json`). This is the backing signal for the
 * `third_party_trackers` compliance finding: our before-consent crawl already sees every request a page
 * fires, so a request to an ad network (with no consent given) is a pre-consent tracking violation the
 * cookie-only checks would miss entirely.
 *
 * **Count only** (product decision): the observed hosts are attacker-influenced and never persisted —
 * only the resulting integer is stored on the scan row — so the raw request domains cannot pollute our
 * storage or logs (§4). Distinctness is by matched *signature*, not by observed host, so a network
 * sharded across `a.doubleclick.net` / `b.doubleclick.net` counts once (one marketing vendor), which is
 * the honest, non-inflatable number to show a visitor.
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
    fun countMarketingTrackers(hosts: Set<String>): Int =
        hosts
            .mapNotNull(matcher::match)
            .filter { it.category == MARKETING_CATEGORY }
            .map { it.domain }
            .toSet()
            .size

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
        const val MARKETING_CATEGORY = "marketing"
    }
}
