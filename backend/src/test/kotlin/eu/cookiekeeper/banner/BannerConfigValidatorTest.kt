package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.BannerCategoryRequest
import eu.cookiekeeper.banner.dto.BannerCategoryTextRequest
import eu.cookiekeeper.banner.dto.BannerConfigUpdateRequest
import eu.cookiekeeper.banner.dto.BannerTextsRequest
import eu.cookiekeeper.banner.dto.BannerThemeRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the banner trust boundary. The document produced here is stored and served to
 * every visitor, so each rejection path (bad position/color, unknown category, unsupported language,
 * missing text) and each normalization rule (GDPR-derived category flags, key/language dedup) matters.
 */
class BannerConfigValidatorTest {
    private fun texts(title: String = "We value your privacy"): BannerTextsRequest =
        BannerTextsRequest(
            title = title,
            description = "We use cookies to enhance your experience.",
            acceptAll = "Accept all",
            rejectAll = "Reject all",
            save = "Save choices",
            preferences = "Manage preferences",
        )

    private fun validRequest(
        position: String = "bottom",
        theme: BannerThemeRequest = BannerThemeRequest("#2563eb", "#ffffff", "#0f172a"),
        categories: List<BannerCategoryRequest> =
            listOf(BannerCategoryRequest("necessary"), BannerCategoryRequest("statistics")),
        languages: List<String> = listOf("en", "de"),
        defaultLanguage: String = "en",
        textsByLang: Map<String, BannerTextsRequest> = mapOf("en" to texts(), "de" to texts("Ihre Privatsphäre")),
        consentLifetimeDays: Int = DEFAULT_CONSENT_LIFETIME_DAYS,
    ): BannerConfigUpdateRequest =
        BannerConfigUpdateRequest(
            position = position,
            theme = theme,
            categories = categories,
            languages = languages,
            defaultLanguage = defaultLanguage,
            texts = textsByLang,
            consentLifetimeDays = consentLifetimeDays,
        )

    @Test
    fun `accepts a well-formed request and preserves category order`() {
        val document = BannerConfigValidator.validate(validRequest())

        assertEquals("bottom", document.position)
        assertEquals(listOf("en", "de"), document.languages)
        assertEquals("en", document.defaultLanguage)
        assertEquals(listOf("necessary", "statistics"), document.categories.map { it.key })
        assertEquals("#2563eb", document.theme.primaryColor)
        assertEquals(setOf("en", "de"), document.texts.keys)
    }

    @Test
    fun `derives category flags from the taxonomy so optional categories are never pre-enabled`() {
        val document =
            BannerConfigValidator.validate(
                validRequest(
                    categories = listOf(BannerCategoryRequest("necessary"), BannerCategoryRequest("marketing")),
                ),
            )

        val necessary = document.categories.first { it.key == "necessary" }
        val marketing = document.categories.first { it.key == "marketing" }
        assertTrue(necessary.required && necessary.enabledByDefault)
        assertTrue(!marketing.required && !marketing.enabledByDefault)
    }

    @Test
    fun `rejects a position outside the allow-list`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(validRequest(position = "floating"))
        }
    }

    @Test
    fun `rejects a non-hex color`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(
                validRequest(theme = BannerThemeRequest("red; background:url(x)", "#ffffff", "#0f172a")),
            )
        }
    }

    @Test
    fun `rejects an unknown category key`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(
                validRequest(categories = listOf(BannerCategoryRequest("necessary"), BannerCategoryRequest("evil"))),
            )
        }
    }

    @Test
    fun `rejects a request that omits the necessary category`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(validRequest(categories = listOf(BannerCategoryRequest("statistics"))))
        }
    }

    @Test
    fun `rejects duplicate category keys`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(
                validRequest(
                    categories = listOf(BannerCategoryRequest("necessary"), BannerCategoryRequest("necessary")),
                ),
            )
        }
    }

    @Test
    fun `rejects an unsupported language`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(
                validRequest(languages = listOf("en", "jp"), textsByLang = mapOf("en" to texts())),
            )
        }
    }

    @Test
    fun `rejects a default language not among the offered languages`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(validRequest(defaultLanguage = "fr"))
        }
    }

    @Test
    fun `rejects a request missing texts for an offered language`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(validRequest(textsByLang = mapOf("en" to texts())))
        }
    }

    @Test
    fun `rejects blank text`() {
        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(
                validRequest(textsByLang = mapOf("en" to texts(title = "   "), "de" to texts())),
            )
        }
    }

    @Test
    fun `fills omitted preferences-panel copy with the shipped translation for that language`() {
        // The customizer's advanced fields are optional; leaving them empty must never publish an
        // English panel to German visitors (or, worse, an empty one).
        val document = BannerConfigValidator.validate(validRequest())

        val german = document.texts.getValue("de")
        assertEquals("Datenschutz-Einstellungen", german.preferencesTitle)
        assertEquals("Schließen", german.close)
        assertEquals("Immer aktiv", german.alwaysActive)
        assertEquals("Unbedingt erforderlich", german.categoryLabels.getValue("necessary").label)
    }

    @Test
    fun `keeps the customer's own panel copy when they provide it`() {
        val custom =
            texts().copy(
                preferencesTitle = "Your choices",
                close = "Dismiss",
                alwaysActive = "Required",
                categoryLabels = mapOf("statistics" to BannerCategoryTextRequest("Analytics", "How you browse.")),
            )

        val document = BannerConfigValidator.validate(validRequest(textsByLang = mapOf("en" to custom, "de" to texts())))

        val english = document.texts.getValue("en")
        assertEquals("Your choices", english.preferencesTitle)
        assertEquals("Dismiss", english.close)
        assertEquals("Analytics", english.categoryLabels.getValue("statistics").label)
        // A category they left alone still gets our wording, not a blank row.
        assertEquals("Strictly necessary", english.categoryLabels.getValue("necessary").label)
    }

    @Test
    fun `keys category labels by the categories actually offered`() {
        val withExtras =
            texts().copy(
                categoryLabels = mapOf("marketing" to BannerCategoryTextRequest("Ads", "Personalized ads.")),
            )

        val document =
            BannerConfigValidator.validate(
                validRequest(textsByLang = mapOf("en" to withExtras, "de" to texts())),
            )

        // `marketing` is not among the offered categories, so its label is dropped rather than shipped
        // to every visitor as dead weight on the config payload.
        assertEquals(
            setOf("necessary", "statistics"),
            document.texts
                .getValue("en")
                .categoryLabels.keys,
        )
    }

    @Test
    fun `rejects an over-long category label`() {
        val tooLong = texts().copy(categoryLabels = mapOf("statistics" to BannerCategoryTextRequest("x".repeat(81), "ok")))

        assertThrows<InvalidBannerConfigException> {
            BannerConfigValidator.validate(validRequest(textsByLang = mapOf("en" to tooLong, "de" to texts())))
        }
    }

    @Test
    fun `accepts every offered consent lifetime`() {
        CONSENT_LIFETIME_DAY_OPTIONS.forEach { days ->
            assertEquals(days, BannerConfigValidator.validate(validRequest(consentLifetimeDays = days)).consentLifetimeDays)
        }
    }

    @Test
    fun `defaults the consent lifetime to 12 months when the client omits it`() {
        assertEquals(DEFAULT_CONSENT_LIFETIME_DAYS, BannerConfigValidator.validate(validRequest()).consentLifetimeDays)
    }

    @Test
    fun `rejects a consent lifetime outside the offered set`() {
        // The value becomes a cookie Max-Age in every visitor's browser — it is a menu, not a number.
        listOf(0, -1, 30, 366, 4000).forEach { days ->
            assertThrows<InvalidBannerConfigException> {
                BannerConfigValidator.validate(validRequest(consentLifetimeDays = days))
            }
        }
    }

    @Test
    fun `normalizes and dedups language codes and text keys`() {
        val document =
            BannerConfigValidator.validate(
                validRequest(
                    languages = listOf("EN", "en-GB", "de"),
                    defaultLanguage = "EN",
                    textsByLang = mapOf("EN" to texts(), "DE" to texts("Ihre Privatsphäre")),
                ),
            )

        assertEquals(listOf("en", "de"), document.languages)
        assertEquals("en", document.defaultLanguage)
        assertEquals(setOf("en", "de"), document.texts.keys)
    }
}
