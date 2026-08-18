package eu.cookiekeeper.policy

/**
 * The pure, repository-free decision about *which row* of a published policy version to serve —
 * extracted from [PolicyReadService] so the language fallback is unit-testable on its own and, more
 * importantly, so the public hosted read and the authenticated dashboard preview provably resolve a
 * language the same way. A preview that picked a different row from the page it is previewing would
 * be worse than no preview at all.
 *
 * Selection is deliberately forgiving rather than strict: a visitor's `?lang=` comes from a link or a
 * browser header, so an unrenderable value must degrade to a readable page, never to a 404. The
 * not-found decisions (unknown public id, nothing published, unverified site) all live in the service
 * — this object never refuses.
 */
object PolicyVersionSelector {
    /**
     * The row to serve from [rows] (all of one version) for [requestedLanguage]: the requested language
     * if this version has it, else the default language, else the lowest language code so the choice is
     * stable across calls rather than dependent on row order. Null only when [rows] is empty, which the
     * caller resolves against the version row it already holds.
     */
    fun choose(
        rows: List<PolicyEntity>,
        requestedLanguage: String?,
    ): PolicyEntity? {
        if (rows.isEmpty()) return null
        val byLanguage = rows.associateBy(PolicyEntity::language)
        val normalizedRequest = requestedLanguage?.let(PolicyLanguages::normalizeOrNull)
        return byLanguage[normalizedRequest]
            ?: byLanguage[PolicyLanguages.DEFAULT]
            ?: rows.minByOrNull(PolicyEntity::language)
    }

    /** The languages this version was published in, sorted, for the page's language switcher. */
    fun availableLanguages(rows: List<PolicyEntity>): List<String> = rows.map(PolicyEntity::language).sorted()
}
