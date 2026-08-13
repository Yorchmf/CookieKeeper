package com.complyr.banner

/**
 * The "Powered by Complyr" attribution the widget renders, in all five supported languages.
 *
 * Deliberately **server-owned**: unlike every other string on the banner this one is not part of
 * [BannerConfigDocument] and cannot be edited from the customizer. Suppressing the attribution is a
 * paid entitlement (`removeBranding`, Pro and above, resolved by `EntitlementService`); if the text
 * were a customer-editable field, any Starter customer could set it to a single space and get the
 * paid outcome for free. Keeping it out of the stored document makes that structurally impossible
 * rather than something a validator has to keep catching.
 *
 * [opensInNewTab] belongs here for the same reason: it is the screen-reader-only suffix on that same
 * link (WCAG 2.2 3.2.5), so it lives and dies with the attribution it annotates.
 */
object WidgetAttributionTexts {
    /** The attribution for [language], falling back to English for anything we ship no copy for. */
    fun forLanguage(language: String): WidgetAttribution = BY_LANGUAGE[language] ?: ENGLISH

    private val ENGLISH =
        WidgetAttribution(
            poweredBy = "Powered by Complyr",
            opensInNewTab = "(opens in a new tab)",
        )

    private val BY_LANGUAGE: Map<String, WidgetAttribution> =
        mapOf(
            "en" to ENGLISH,
            "de" to
                WidgetAttribution(
                    poweredBy = "Bereitgestellt von Complyr",
                    opensInNewTab = "(wird in einem neuen Tab geöffnet)",
                ),
            "fr" to
                WidgetAttribution(
                    poweredBy = "Propulsé par Complyr",
                    opensInNewTab = "(ouvre un nouvel onglet)",
                ),
            "es" to
                WidgetAttribution(
                    poweredBy = "Con tecnología de Complyr",
                    opensInNewTab = "(se abre en una pestaña nueva)",
                ),
            "it" to
                WidgetAttribution(
                    poweredBy = "Con tecnologia Complyr",
                    opensInNewTab = "(si apre in una nuova scheda)",
                ),
        )
}

data class WidgetAttribution(
    val poweredBy: String,
    val opensInNewTab: String,
)
