package com.complyr.banner

import com.complyr.banner.dto.BannerCategoryRequest
import com.complyr.banner.dto.BannerConfigUpdateRequest
import com.complyr.banner.dto.BannerTextsRequest
import com.complyr.banner.dto.BannerThemeRequest
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
    ): BannerConfigUpdateRequest =
        BannerConfigUpdateRequest(
            position = position,
            theme = theme,
            categories = categories,
            languages = languages,
            defaultLanguage = defaultLanguage,
            texts = textsByLang,
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
