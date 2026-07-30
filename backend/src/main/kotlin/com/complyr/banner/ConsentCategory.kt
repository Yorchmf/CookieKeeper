package com.complyr.banner

/**
 * The canonical consent-category taxonomy shared by the banner, the scanner classifier, and
 * consent validation. `necessary` is always required (cannot be rejected); the rest are opt-in.
 * Keys are the stable wire identifiers stored in banner configs and consent events.
 */
enum class ConsentCategory(
    val key: String,
    val required: Boolean,
) {
    NECESSARY("necessary", required = true),
    PREFERENCES("preferences", required = false),
    STATISTICS("statistics", required = false),
    MARKETING("marketing", required = false),
    ;

    companion object {
        /** All category keys — the fallback allow-list when a site has no published config yet. */
        val KEYS: Set<String> = entries.map { it.key }.toSet()
    }
}
