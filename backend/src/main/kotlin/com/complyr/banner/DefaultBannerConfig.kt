package com.complyr.banner

/**
 * The out-of-the-box banner configuration seeded for every new site so the widget works the
 * moment the snippet is embedded — before the customer customizes anything. Ships all five
 * supported languages (constraint #6: i18n from day one) and the full category taxonomy with
 * GDPR-safe defaults (only `necessary` on before an explicit choice).
 */
object DefaultBannerConfig {
    const val FIRST_VERSION: Int = 1

    fun document(): BannerConfigDocument =
        BannerConfigDocument(
            position = "bottom",
            theme =
                BannerTheme(
                    primaryColor = "#2563eb",
                    background = "#ffffff",
                    textColor = "#0f172a",
                ),
            categories =
                ConsentCategory.entries.map { category ->
                    BannerCategory(
                        key = category.key,
                        required = category.required,
                        // GDPR: nothing but strictly-necessary may be pre-enabled.
                        enabledByDefault = category.required,
                    )
                },
            languages = TEXTS.keys.toList(),
            defaultLanguage = "en",
            texts = TEXTS,
        )

    private val TEXTS: Map<String, BannerTexts> =
        mapOf(
            "en" to
                BannerTexts(
                    title = "We value your privacy",
                    description = "We use cookies to enhance your experience. Choose which categories you allow.",
                    acceptAll = "Accept all",
                    rejectAll = "Reject all",
                    save = "Save choices",
                    preferences = "Manage preferences",
                ),
            "de" to
                BannerTexts(
                    title = "Ihre Privatsphäre ist uns wichtig",
                    description = "Wir verwenden Cookies, um Ihr Erlebnis zu verbessern. Wählen Sie die erlaubten Kategorien.",
                    acceptAll = "Alle akzeptieren",
                    rejectAll = "Alle ablehnen",
                    save = "Auswahl speichern",
                    preferences = "Einstellungen verwalten",
                ),
            "fr" to
                BannerTexts(
                    title = "Nous respectons votre vie privée",
                    description = "Nous utilisons des cookies pour améliorer votre expérience. Choisissez les catégories autorisées.",
                    acceptAll = "Tout accepter",
                    rejectAll = "Tout refuser",
                    save = "Enregistrer les choix",
                    preferences = "Gérer les préférences",
                ),
            "es" to
                BannerTexts(
                    title = "Valoramos su privacidad",
                    description = "Usamos cookies para mejorar su experiencia. Elija qué categorías permite.",
                    acceptAll = "Aceptar todo",
                    rejectAll = "Rechazar todo",
                    save = "Guardar opciones",
                    preferences = "Gestionar preferencias",
                ),
            "it" to
                BannerTexts(
                    title = "Teniamo alla tua privacy",
                    description = "Utilizziamo i cookie per migliorare la tua esperienza. Scegli quali categorie consentire.",
                    acceptAll = "Accetta tutto",
                    rejectAll = "Rifiuta tutto",
                    save = "Salva le scelte",
                    preferences = "Gestisci preferenze",
                ),
        )
}
