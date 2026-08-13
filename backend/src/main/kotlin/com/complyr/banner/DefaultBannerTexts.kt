package com.complyr.banner

/**
 * The shipped banner copy for all five supported languages (constraint #6). Used twice:
 *
 *  1. seeded into every new site's config ([DefaultBannerConfig]), so a customer starts from
 *     translated wording rather than blanks;
 *  2. as the per-language backfill for any field a stored document leaves blank
 *     ([BannerTextDefaults]) — which is how configs written before ADR-19 Slice 2 acquire the
 *     preferences-panel wording without a data migration.
 *
 * Everything here is customer-editable. The "Powered by Complyr" attribution deliberately lives
 * in [WidgetAttributionTexts] instead — see there for why.
 */
object DefaultBannerTexts {
    val BY_LANGUAGE: Map<String, BannerTexts> =
        mapOf(
            "en" to
                BannerTexts(
                    title = "We value your privacy",
                    description = "We use cookies to enhance your experience. Choose which categories you allow.",
                    acceptAll = "Accept all",
                    rejectAll = "Reject all",
                    save = "Save choices",
                    preferences = "Manage preferences",
                    preferencesTitle = "Privacy preferences",
                    close = "Close",
                    alwaysActive = "Always active",
                    categoryLabels =
                        categoryLabels(
                            necessary =
                                BannerCategoryText(
                                    label = "Strictly necessary",
                                    description = "Required for the site to work. These cannot be switched off.",
                                ),
                            preferences =
                                BannerCategoryText(
                                    label = "Preferences",
                                    description = "Remember your settings and choices on this site.",
                                ),
                            statistics =
                                BannerCategoryText(
                                    label = "Statistics",
                                    description = "Help us understand how visitors use the site, anonymously.",
                                ),
                            marketing =
                                BannerCategoryText(
                                    label = "Marketing",
                                    description = "Used to personalize ads and measure their performance.",
                                ),
                        ),
                ),
            "de" to
                BannerTexts(
                    title = "Ihre Privatsphäre ist uns wichtig",
                    description = "Wir verwenden Cookies, um Ihr Erlebnis zu verbessern. Wählen Sie die erlaubten Kategorien.",
                    acceptAll = "Alle akzeptieren",
                    rejectAll = "Alle ablehnen",
                    save = "Auswahl speichern",
                    preferences = "Einstellungen verwalten",
                    preferencesTitle = "Datenschutz-Einstellungen",
                    close = "Schließen",
                    alwaysActive = "Immer aktiv",
                    categoryLabels =
                        categoryLabels(
                            necessary =
                                BannerCategoryText(
                                    label = "Unbedingt erforderlich",
                                    description = "Für den Betrieb der Website erforderlich. Diese können nicht deaktiviert werden.",
                                ),
                            preferences =
                                BannerCategoryText(
                                    label = "Präferenzen",
                                    description = "Speichern Ihre Einstellungen und Auswahl auf dieser Website.",
                                ),
                            statistics =
                                BannerCategoryText(
                                    label = "Statistiken",
                                    description = "Helfen uns anonym zu verstehen, wie Besucher die Website nutzen.",
                                ),
                            marketing =
                                BannerCategoryText(
                                    label = "Marketing",
                                    description = "Dienen dazu, Werbung zu personalisieren und deren Leistung zu messen.",
                                ),
                        ),
                ),
            "fr" to
                BannerTexts(
                    title = "Nous respectons votre vie privée",
                    description = "Nous utilisons des cookies pour améliorer votre expérience. Choisissez les catégories autorisées.",
                    acceptAll = "Tout accepter",
                    rejectAll = "Tout refuser",
                    save = "Enregistrer les choix",
                    preferences = "Gérer les préférences",
                    preferencesTitle = "Préférences de confidentialité",
                    close = "Fermer",
                    alwaysActive = "Toujours actif",
                    categoryLabels =
                        categoryLabels(
                            necessary =
                                BannerCategoryText(
                                    label = "Strictement nécessaires",
                                    description = "Indispensables au fonctionnement du site. Ils ne peuvent pas être désactivés.",
                                ),
                            preferences =
                                BannerCategoryText(
                                    label = "Préférences",
                                    description = "Mémorisent vos réglages et vos choix sur ce site.",
                                ),
                            statistics =
                                BannerCategoryText(
                                    label = "Statistiques",
                                    description = "Nous aident à comprendre, de façon anonyme, comment les visiteurs utilisent le site.",
                                ),
                            marketing =
                                BannerCategoryText(
                                    label = "Marketing",
                                    description = "Servent à personnaliser les publicités et à mesurer leur performance.",
                                ),
                        ),
                ),
            "es" to
                BannerTexts(
                    title = "Valoramos su privacidad",
                    description = "Usamos cookies para mejorar su experiencia. Elija qué categorías permite.",
                    acceptAll = "Aceptar todo",
                    rejectAll = "Rechazar todo",
                    save = "Guardar opciones",
                    preferences = "Gestionar preferencias",
                    preferencesTitle = "Preferencias de privacidad",
                    close = "Cerrar",
                    alwaysActive = "Siempre activo",
                    categoryLabels =
                        categoryLabels(
                            necessary =
                                BannerCategoryText(
                                    label = "Estrictamente necesarias",
                                    description = "Imprescindibles para que el sitio funcione. No se pueden desactivar.",
                                ),
                            preferences =
                                BannerCategoryText(
                                    label = "Preferencias",
                                    description = "Recuerdan sus ajustes y sus elecciones en este sitio.",
                                ),
                            statistics =
                                BannerCategoryText(
                                    label = "Estadísticas",
                                    description = "Nos ayudan a entender de forma anónima cómo se usa el sitio.",
                                ),
                            marketing =
                                BannerCategoryText(
                                    label = "Marketing",
                                    description = "Se usan para personalizar anuncios y medir su rendimiento.",
                                ),
                        ),
                ),
            "it" to
                BannerTexts(
                    title = "Teniamo alla tua privacy",
                    description = "Utilizziamo i cookie per migliorare la tua esperienza. Scegli quali categorie consentire.",
                    acceptAll = "Accetta tutto",
                    rejectAll = "Rifiuta tutto",
                    save = "Salva le scelte",
                    preferences = "Gestisci preferenze",
                    preferencesTitle = "Preferenze sulla privacy",
                    close = "Chiudi",
                    alwaysActive = "Sempre attivo",
                    categoryLabels =
                        categoryLabels(
                            necessary =
                                BannerCategoryText(
                                    label = "Strettamente necessari",
                                    description = "Indispensabili per il funzionamento del sito. Non possono essere disattivati.",
                                ),
                            preferences =
                                BannerCategoryText(
                                    label = "Preferenze",
                                    description = "Memorizzano le tue impostazioni e le tue scelte su questo sito.",
                                ),
                            statistics =
                                BannerCategoryText(
                                    label = "Statistiche",
                                    description = "Ci aiutano a capire in forma anonima come i visitatori usano il sito.",
                                ),
                            marketing =
                                BannerCategoryText(
                                    label = "Marketing",
                                    description = "Servono a personalizzare gli annunci e a misurarne le prestazioni.",
                                ),
                        ),
                ),
        )

    /** English is the last resort when a stored document offers a language we ship no copy for. */
    val ENGLISH: BannerTexts = requireNotNull(BY_LANGUAGE["en"]) { "English default texts are missing" }

    /** Named-argument constructor so each language's block reads as a table rather than a tuple list. */
    private fun categoryLabels(
        necessary: BannerCategoryText,
        preferences: BannerCategoryText,
        statistics: BannerCategoryText,
        marketing: BannerCategoryText,
    ): Map<String, BannerCategoryText> =
        mapOf(
            ConsentCategory.NECESSARY.key to necessary,
            ConsentCategory.PREFERENCES.key to preferences,
            ConsentCategory.STATISTICS.key to statistics,
            ConsentCategory.MARKETING.key to marketing,
        )
}
