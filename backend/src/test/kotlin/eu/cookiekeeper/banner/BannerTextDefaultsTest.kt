package eu.cookiekeeper.banner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-19 Slice 2 shipped without a data migration: every site created before it has a stored config
 * with no preferences-panel copy at all. The guarantee that makes that safe is this backfill running
 * on every read — if it regresses, live banners lose their panel labels, in every language, silently.
 */
class BannerTextDefaultsTest {
    private fun legacyTexts(title: String) =
        BannerTexts(
            title = title,
            description = "We use cookies.",
            acceptAll = "Accept all",
            rejectAll = "Reject all",
            save = "Save choices",
            preferences = "Manage preferences",
        )

    private fun legacyDocument(languages: List<String> = listOf("en", "de")) =
        BannerConfigDocument(
            position = "bottom",
            theme = BannerTheme(primaryColor = "#2563eb", background = "#ffffff", textColor = "#0f172a"),
            categories =
                listOf(
                    BannerCategory(key = "necessary", required = true, enabledByDefault = true),
                    BannerCategory(key = "statistics", required = false, enabledByDefault = false),
                ),
            languages = languages,
            defaultLanguage = languages.first(),
            texts = languages.associateWith { legacyTexts("Title $it") },
        )

    @Test
    fun `fills a pre-Slice-2 document with the shipped copy for each language`() {
        val completed = BannerTextDefaults.complete(legacyDocument())

        val german = completed.texts.getValue("de")
        assertEquals("Datenschutz-Einstellungen", german.preferencesTitle)
        assertEquals("Schließen", german.close)
        assertEquals("Immer aktiv", german.alwaysActive)
        assertEquals("Unbedingt erforderlich", german.categoryLabels.getValue("necessary").label)
        assertEquals("Statistiken", german.categoryLabels.getValue("statistics").label)
    }

    @Test
    fun `leaves the customer's own copy untouched`() {
        val document = legacyDocument(listOf("en"))
        val customized =
            document.copy(
                texts =
                    mapOf(
                        "en" to
                            document.texts.getValue("en").copy(
                                preferencesTitle = "Your choices",
                                categoryLabels = mapOf("statistics" to BannerCategoryText("Analytics", "How you browse.")),
                            ),
                    ),
            )

        val completed = BannerTextDefaults.complete(customized)

        val english = completed.texts.getValue("en")
        assertEquals("Your choices", english.preferencesTitle)
        assertEquals("Analytics", english.categoryLabels.getValue("statistics").label)
        // Only the gaps are filled — the untouched category still gets our wording.
        assertEquals("Close", english.close)
        assertEquals("Strictly necessary", english.categoryLabels.getValue("necessary").label)
    }

    @Test
    fun `only labels the categories the config offers`() {
        val completed = BannerTextDefaults.complete(legacyDocument())

        assertEquals(
            setOf("necessary", "statistics"),
            completed.texts
                .getValue("en")
                .categoryLabels.keys,
        )
    }

    @Test
    fun `falls back to English for a language we ship no copy for`() {
        val completed = BannerTextDefaults.complete(legacyDocument(listOf("pt")))

        val portuguese = completed.texts.getValue("pt")
        assertEquals("Privacy preferences", portuguese.preferencesTitle)
        assertTrue(
            portuguese.categoryLabels
                .getValue("necessary")
                .label
                .isNotBlank(),
        )
    }

    @Test
    fun `does not repair the banner copy the validator already guarantees`() {
        // Blank title/description would mean the validator let something through; masking that here
        // would turn a validation regression into an invisible one.
        val document = legacyDocument(listOf("en"))
        val blanked = document.copy(texts = mapOf("en" to document.texts.getValue("en").copy(title = "")))

        assertEquals(
            "",
            BannerTextDefaults
                .complete(blanked)
                .texts
                .getValue("en")
                .title,
        )
    }

    @Test
    fun `every shipped language covers the whole category taxonomy`() {
        // A missing entry here would surface as an unlabelled row in the visitor's panel.
        DefaultBannerTexts.BY_LANGUAGE.forEach { (language, texts) ->
            ConsentCategory.entries.forEach { category ->
                val label = texts.categoryLabels[category.key]
                assertTrue(
                    label != null && label.label.isNotBlank() && label.description.isNotBlank(),
                    "missing ${category.key} copy for $language",
                )
            }
            assertTrue(texts.preferencesTitle.isNotBlank() && texts.close.isNotBlank() && texts.alwaysActive.isNotBlank())
        }
    }
}
