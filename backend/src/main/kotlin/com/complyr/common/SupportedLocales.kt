package com.complyr.common

/**
 * The five product languages Complyr supports from day one (CLAUDE.md #6), as stable lowercase
 * ISO-639-1 codes — the same wire identifiers used by banner configs, policies, and consent events.
 * The single source of truth other locale helpers (e.g. [com.complyr.policy.PolicyLanguages]) delegate to.
 */
object SupportedLocales {
    const val DEFAULT: String = "en"

    val CODES: List<String> = listOf("en", "de", "fr", "es", "it")

    fun isSupported(code: String): Boolean = code in CODES

    /** Normalizes a raw code ("DE", "de-DE") to a supported lowercase code, or null if unsupported. */
    fun normalizeOrNull(raw: String): String? {
        val code = raw.trim().lowercase().substringBefore('-')
        return code.takeIf(::isSupported)
    }
}
