package com.complyr.policy

/**
 * The five languages the policy generator ships from day one (CLAUDE.md #6), and the default used
 * when a requested language is not available for a site. Kept as stable lowercase ISO-639-1 codes —
 * the same wire identifiers the banner config and consent events use.
 */
object PolicyLanguages {
    const val DEFAULT: String = "en"

    val SUPPORTED: List<String> = listOf("en", "de", "fr", "es", "it")

    fun isSupported(language: String): Boolean = language in SUPPORTED

    /** Normalizes a raw request language ("DE", "de-DE") to a supported code, or null if unsupported. */
    fun normalizeOrNull(raw: String): String? {
        val code = raw.trim().lowercase().substringBefore('-')
        return code.takeIf(::isSupported)
    }
}
