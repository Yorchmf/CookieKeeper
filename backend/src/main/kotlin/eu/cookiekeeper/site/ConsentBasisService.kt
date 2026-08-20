package eu.cookiekeeper.site

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Owns a site's **consent basis** — the versioned answer to "what were this site's visitors actually
 * consenting to?" (BACKLOG #18).
 *
 * A consent choice is only valid for the purposes it was collected for. A site that adds a marketing
 * tracker months after a visitor clicked "Accept" is running that tracker on consent nobody gave, so
 * once a consent-decidable category comes newly into use the site's basis version goes up, the widget
 * sees a higher version than the one stamped in the visitor's cookie, and it asks again.
 *
 * Three rules make this safe to ship to an existing customer base:
 *
 *  1. **Seed, don't bump.** A site whose basis was never recorded gets its first observation written at
 *     the version it already has. Deploying this feature must not re-prompt every visitor on the
 *     internet; only a change observed *after* we started watching counts.
 *  2. **Grow, never shrink.** The stored set is the union of everything ever seen. A tracker that
 *     disappears and returns is not a second re-prompt for a purpose the visitor already answered.
 *  3. **Compare against the STORED basis, not the previous scan.** The two differ: a category can
 *     arrive across several scans, or from the signature DB learning what an already-present cookie
 *     does. Only the stored basis knows what the recorded consents were collected under.
 *
 * The API takes plain category keys rather than a scan, so nothing here depends on the scanner — the
 * single definition of *which* categories a scan's findings put in use lives with the scan diff
 * ([eu.cookiekeeper.scan.ScanFindings]).
 */
@Service
class ConsentBasisService(
    private val siteRepository: SiteRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConsentBasisService::class.java)

    /**
     * Fold one observation of [categoriesInUse] (decidable keys only — never `necessary`, which cannot
     * be rejected) into [siteId]'s basis. Returns the categories that were newly added and therefore
     * caused a re-prompt, empty when nothing changed. Idempotent: the same observation twice is a
     * no-op the second time.
     */
    @Transactional
    fun record(
        siteId: UUID,
        categoriesInUse: Set<String>,
    ): Set<String> {
        val observed = categoriesInUse.filter { it.isNotBlank() }.toSet()
        val site = siteRepository.findById(siteId).orElse(null) ?: return emptySet()

        val stored = site.consentBasisCategories
        return if (stored == null) seed(site, observed) else grow(site, stored, observed)
    }

    /** Rule 1: first observation, written at the version the site already has. Never a re-prompt. */
    private fun seed(
        site: SiteEntity,
        observed: Set<String>,
    ): Set<String> {
        siteRepository.seedConsentBasis(site.id, format(observed))
        log.debug("Seeded consent basis for site {} at version {}", site.id, site.consentBasisVersion)
        return emptySet()
    }

    /** Rules 2 and 3: union against the STORED basis, and only what it does not already cover. */
    private fun grow(
        site: SiteEntity,
        stored: String,
        observed: Set<String>,
    ): Set<String> {
        val added = observed - parse(stored)
        if (added.isEmpty()) return emptySet()
        return if (bump(site, stored, added)) added else emptySet()
    }

    /** The compare-and-set write. False when another scan for this site got there first. */
    private fun bump(
        site: SiteEntity,
        stored: String,
        added: Set<String>,
    ): Boolean {
        val updated =
            siteRepository.bumpConsentBasis(
                siteId = site.id,
                categories = format(parse(stored) + added),
                added = format(added),
                changedAt = clock.instant(),
                expectedCategories = stored,
            )
        if (updated == 0) {
            // Another scan for this site bumped between our read and our write. Its observation is the
            // one that stands; ours is either already covered or picked up by the next completed scan.
            log.debug("Consent basis for site {} changed concurrently; skipping bump", site.id)
            return false
        }

        log.info(
            "Consent basis bumped for site {} to version {}: {} newly in use — visitors will be asked again",
            site.id,
            site.consentBasisVersion + 1,
            added.sorted(),
        )
        return true
    }

    /** Sorted and comma-joined, so the stored value is stable and the compare-and-set can key on it. */
    private fun format(categories: Set<String>): String = categories.sorted().joinToString(SEPARATOR)

    private fun parse(stored: String): Set<String> = stored.split(SEPARATOR).filter { it.isNotBlank() }.toSet()

    private companion object {
        const val SEPARATOR = ","
    }
}
