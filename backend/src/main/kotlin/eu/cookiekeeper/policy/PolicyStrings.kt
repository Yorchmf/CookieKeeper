package eu.cookiekeeper.policy

import eu.cookiekeeper.banner.ConsentCategory

/**
 * Per-language text bundle for the generated cookie policy. Mirrors the in-code i18n approach of
 * [eu.cookiekeeper.banner.DefaultBannerConfig] (constraint #6: no hardcoded user-facing strings, all five
 * languages from day one) rather than a runtime `MessageSource`, so a legal document's wording is
 * deterministic, reviewable in one place, and never subject to locale-resolution surprises.
 *
 * [intro] and [contact] carry the placeholders [PLACEHOLDER_COMPANY]/[PLACEHOLDER_WEBSITE] and
 * [PLACEHOLDER_EMAIL]; [PolicyRenderer] substitutes them with HTML-escaped values.
 */
data class PolicyStrings(
    val title: String,
    val updatedLabel: String,
    val intro: String,
    val contact: String,
    val addressLabel: String,
    val noCookies: String,
    val colName: String,
    val colProvider: String,
    val colExpiry: String,
    /** Shown in the provider column when a cookie has no known provider. */
    val unknownProvider: String,
    /** Shown in the expiry column when a cookie has no stored expiry (a session cookie). */
    val sessionExpiry: String,
    /** Category name + description keyed by [ConsentCategory.key]; covers every category. */
    val categories: Map<String, CategoryText>,
    /** Heading + blurb for cookies the scanner could not classify. */
    val other: CategoryText,
) {
    fun category(key: String): CategoryText = categories[key] ?: other

    companion object {
        const val PLACEHOLDER_COMPANY = "{company}"
        const val PLACEHOLDER_WEBSITE = "{website}"
        const val PLACEHOLDER_EMAIL = "{email}"

        private val NECESSARY = ConsentCategory.NECESSARY.key
        private val PREFERENCES = ConsentCategory.PREFERENCES.key
        private val STATISTICS = ConsentCategory.STATISTICS.key
        private val MARKETING = ConsentCategory.MARKETING.key

        /** Bundle for [language], falling back to [PolicyLanguages.DEFAULT] for anything unsupported. */
        fun forLanguage(language: String): PolicyStrings = BUNDLES[language] ?: BUNDLES.getValue(PolicyLanguages.DEFAULT)

        private val EN =
            PolicyStrings(
                title = "Cookie Policy",
                updatedLabel = "Last updated",
                intro =
                    "$PLACEHOLDER_COMPANY operates the website $PLACEHOLDER_WEBSITE and uses cookies and " +
                        "similar technologies to run this site and, with your consent, to improve it and " +
                        "measure its use. This policy explains which cookies we use and why.",
                contact = "If you have any questions about this cookie policy, contact us at $PLACEHOLDER_EMAIL.",
                addressLabel = "Postal address",
                noCookies = "Our latest scan found no cookies on this website other than those strictly necessary to operate it.",
                colName = "Cookie",
                colProvider = "Provider",
                colExpiry = "Expiry",
                unknownProvider = "—",
                sessionExpiry = "Session",
                categories =
                    mapOf(
                        NECESSARY to
                            CategoryText(
                                "Strictly necessary cookies",
                                "These cookies are required for the website to function and cannot be switched off. " +
                                    "They do not require consent.",
                            ),
                        PREFERENCES to
                            CategoryText(
                                "Preference cookies",
                                "These cookies let the website remember choices you make, such as your language or region.",
                            ),
                        STATISTICS to
                            CategoryText(
                                "Statistics cookies",
                                "These cookies help us understand how visitors use the website by collecting information " +
                                    "anonymously.",
                            ),
                        MARKETING to
                            CategoryText(
                                "Marketing cookies",
                                "These cookies are used to deliver advertising relevant to you and to measure its effectiveness.",
                            ),
                    ),
                other =
                    CategoryText(
                        "Other cookies",
                        "These cookies were detected on the website but have not yet been classified.",
                    ),
            )

        private val DE =
            PolicyStrings(
                title = "Cookie-Richtlinie",
                updatedLabel = "Zuletzt aktualisiert",
                intro =
                    "$PLACEHOLDER_COMPANY betreibt die Website $PLACEHOLDER_WEBSITE und verwendet Cookies und " +
                        "ähnliche Technologien, um diese Website zu betreiben und sie – mit Ihrer Einwilligung – zu " +
                        "verbessern und ihre Nutzung zu messen. Diese Richtlinie erläutert, welche Cookies wir " +
                        "verwenden und warum.",
                contact = "Bei Fragen zu dieser Cookie-Richtlinie erreichen Sie uns unter $PLACEHOLDER_EMAIL.",
                addressLabel = "Postanschrift",
                noCookies =
                    "Unser letzter Scan hat auf dieser Website keine Cookies gefunden, außer den zum Betrieb " +
                        "unbedingt erforderlichen.",
                colName = "Cookie",
                colProvider = "Anbieter",
                colExpiry = "Ablauf",
                unknownProvider = "—",
                sessionExpiry = "Sitzung",
                categories =
                    mapOf(
                        NECESSARY to
                            CategoryText(
                                "Unbedingt erforderliche Cookies",
                                "Diese Cookies sind für den Betrieb der Website erforderlich und können nicht " +
                                    "deaktiviert werden. Sie bedürfen keiner Einwilligung.",
                            ),
                        PREFERENCES to
                            CategoryText(
                                "Präferenz-Cookies",
                                "Mit diesen Cookies kann sich die Website Ihre Entscheidungen merken, etwa Ihre " +
                                    "Sprache oder Region.",
                            ),
                        STATISTICS to
                            CategoryText(
                                "Statistik-Cookies",
                                "Diese Cookies helfen uns zu verstehen, wie Besucher die Website nutzen, indem sie " +
                                    "Informationen anonym erfassen.",
                            ),
                        MARKETING to
                            CategoryText(
                                "Marketing-Cookies",
                                "Diese Cookies werden verwendet, um Ihnen relevante Werbung anzuzeigen und deren " +
                                    "Wirksamkeit zu messen.",
                            ),
                    ),
                other =
                    CategoryText(
                        "Sonstige Cookies",
                        "Diese Cookies wurden auf der Website erkannt, aber noch nicht klassifiziert.",
                    ),
            )

        private val FR =
            PolicyStrings(
                title = "Politique relative aux cookies",
                updatedLabel = "Dernière mise à jour",
                intro =
                    "$PLACEHOLDER_COMPANY exploite le site web $PLACEHOLDER_WEBSITE et utilise des cookies et des " +
                        "technologies similaires pour faire fonctionner ce site et, avec votre consentement, " +
                        "l'améliorer et en mesurer l'utilisation. Cette politique explique quels cookies nous " +
                        "utilisons et pourquoi.",
                contact =
                    "Pour toute question concernant cette politique relative aux cookies, contactez-nous à " +
                        "l'adresse $PLACEHOLDER_EMAIL.",
                addressLabel = "Adresse postale",
                noCookies =
                    "Notre dernière analyse n'a détecté aucun cookie sur ce site web, hormis ceux strictement " +
                        "nécessaires à son fonctionnement.",
                colName = "Cookie",
                colProvider = "Fournisseur",
                colExpiry = "Expiration",
                unknownProvider = "—",
                sessionExpiry = "Session",
                categories =
                    mapOf(
                        NECESSARY to
                            CategoryText(
                                "Cookies strictement nécessaires",
                                "Ces cookies sont indispensables au fonctionnement du site et ne peuvent pas être " +
                                    "désactivés. Ils ne nécessitent pas de consentement.",
                            ),
                        PREFERENCES to
                            CategoryText(
                                "Cookies de préférences",
                                "Ces cookies permettent au site de mémoriser vos choix, comme votre langue ou votre région.",
                            ),
                        STATISTICS to
                            CategoryText(
                                "Cookies de statistiques",
                                "Ces cookies nous aident à comprendre comment les visiteurs utilisent le site en " +
                                    "collectant des informations de manière anonyme.",
                            ),
                        MARKETING to
                            CategoryText(
                                "Cookies marketing",
                                "Ces cookies servent à vous proposer des publicités pertinentes et à en mesurer l'efficacité.",
                            ),
                    ),
                other =
                    CategoryText(
                        "Autres cookies",
                        "Ces cookies ont été détectés sur le site mais n'ont pas encore été classés.",
                    ),
            )

        private val ES =
            PolicyStrings(
                title = "Política de cookies",
                updatedLabel = "Última actualización",
                intro =
                    "$PLACEHOLDER_COMPANY gestiona el sitio web $PLACEHOLDER_WEBSITE y utiliza cookies y tecnologías " +
                        "similares para operar este sitio y, con su consentimiento, mejorarlo y medir su uso. Esta " +
                        "política explica qué cookies utilizamos y por qué.",
                contact = "Si tiene alguna pregunta sobre esta política de cookies, contáctenos en $PLACEHOLDER_EMAIL.",
                addressLabel = "Dirección postal",
                noCookies =
                    "Nuestro último análisis no encontró cookies en este sitio web, aparte de las estrictamente " +
                        "necesarias para su funcionamiento.",
                colName = "Cookie",
                colProvider = "Proveedor",
                colExpiry = "Caducidad",
                unknownProvider = "—",
                sessionExpiry = "Sesión",
                categories =
                    mapOf(
                        NECESSARY to
                            CategoryText(
                                "Cookies estrictamente necesarias",
                                "Estas cookies son necesarias para que el sitio web funcione y no se pueden desactivar. " +
                                    "No requieren consentimiento.",
                            ),
                        PREFERENCES to
                            CategoryText(
                                "Cookies de preferencias",
                                "Estas cookies permiten que el sitio web recuerde sus elecciones, como su idioma o región.",
                            ),
                        STATISTICS to
                            CategoryText(
                                "Cookies de estadísticas",
                                "Estas cookies nos ayudan a entender cómo utilizan el sitio web los visitantes " +
                                    "recopilando información de forma anónima.",
                            ),
                        MARKETING to
                            CategoryText(
                                "Cookies de marketing",
                                "Estas cookies se utilizan para ofrecerle publicidad relevante y medir su eficacia.",
                            ),
                    ),
                other =
                    CategoryText(
                        "Otras cookies",
                        "Estas cookies se detectaron en el sitio web pero aún no se han clasificado.",
                    ),
            )

        private val IT =
            PolicyStrings(
                title = "Informativa sui cookie",
                updatedLabel = "Ultimo aggiornamento",
                intro =
                    "$PLACEHOLDER_COMPANY gestisce il sito web $PLACEHOLDER_WEBSITE e utilizza cookie e tecnologie " +
                        "simili per far funzionare questo sito e, con il tuo consenso, per migliorarlo e misurarne " +
                        "l'utilizzo. La presente informativa spiega quali cookie utilizziamo e perché.",
                contact = "Per qualsiasi domanda su questa informativa sui cookie, contattaci all'indirizzo $PLACEHOLDER_EMAIL.",
                addressLabel = "Indirizzo postale",
                noCookies =
                    "La nostra ultima scansione non ha rilevato cookie su questo sito web, a parte quelli " +
                        "strettamente necessari al suo funzionamento.",
                colName = "Cookie",
                colProvider = "Fornitore",
                colExpiry = "Scadenza",
                unknownProvider = "—",
                sessionExpiry = "Sessione",
                categories =
                    mapOf(
                        NECESSARY to
                            CategoryText(
                                "Cookie strettamente necessari",
                                "Questi cookie sono necessari al funzionamento del sito web e non possono essere " +
                                    "disattivati. Non richiedono consenso.",
                            ),
                        PREFERENCES to
                            CategoryText(
                                "Cookie di preferenze",
                                "Questi cookie consentono al sito web di ricordare le tue scelte, come la lingua o la regione.",
                            ),
                        STATISTICS to
                            CategoryText(
                                "Cookie statistici",
                                "Questi cookie ci aiutano a capire come i visitatori utilizzano il sito web raccogliendo " +
                                    "informazioni in forma anonima.",
                            ),
                        MARKETING to
                            CategoryText(
                                "Cookie di marketing",
                                "Questi cookie vengono utilizzati per offrirti pubblicità pertinente e per misurarne l'efficacia.",
                            ),
                    ),
                other =
                    CategoryText(
                        "Altri cookie",
                        "Questi cookie sono stati rilevati sul sito web ma non sono ancora stati classificati.",
                    ),
            )

        private val BUNDLES: Map<String, PolicyStrings> =
            mapOf(
                "en" to EN,
                "de" to DE,
                "fr" to FR,
                "es" to ES,
                "it" to IT,
            )
    }
}

/** A category's localized heading and explanatory blurb. */
data class CategoryText(
    val name: String,
    val description: String,
)
