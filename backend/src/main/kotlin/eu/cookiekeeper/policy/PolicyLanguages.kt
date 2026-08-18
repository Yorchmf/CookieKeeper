package eu.cookiekeeper.policy

import eu.cookiekeeper.common.SupportedLocales

/**
 * The five languages the policy generator ships from day one (CLAUDE.md #6), and the default used
 * when a requested language is not available for a site. A thin policy-scoped alias over
 * [SupportedLocales] (the single source of truth) so existing call sites keep their names.
 */
object PolicyLanguages {
    const val DEFAULT: String = SupportedLocales.DEFAULT

    val SUPPORTED: List<String> = SupportedLocales.CODES

    fun isSupported(language: String): Boolean = SupportedLocales.isSupported(language)

    /** Normalizes a raw request language ("DE", "de-DE") to a supported code, or null if unsupported. */
    fun normalizeOrNull(raw: String): String? = SupportedLocales.normalizeOrNull(raw)
}
