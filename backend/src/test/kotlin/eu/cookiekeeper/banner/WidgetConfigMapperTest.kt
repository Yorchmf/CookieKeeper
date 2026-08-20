package eu.cookiekeeper.banner

import eu.cookiekeeper.banner.dto.WidgetConfigResponse
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema half of ADR-19. This mapper is the only place the stored config and the widget's
 * contract are reconciled, and a mismatch is invisible in the dashboard: the widget silently
 * discards a config it cannot parse and renders its built-in default instead. So each divergence
 * — the computed `buttonText`, `center` → `bottom`, `key` → `id`, `description` → `message` — is
 * pinned here.
 */
class WidgetConfigMapperTest {
    private fun texts(title: String = "We value your privacy") =
        BannerTexts(
            title = title,
            description = "We use cookies to enhance your experience.",
            acceptAll = "Accept all",
            rejectAll = "Reject all",
            save = "Save choices",
            preferences = "Manage preferences",
            preferencesTitle = "Privacy preferences",
            close = "Close",
            alwaysActive = "Always active",
            categoryLabels =
                mapOf(
                    "necessary" to BannerCategoryText(label = "Strictly necessary", description = "Required to work."),
                    "statistics" to BannerCategoryText(label = "Statistics", description = "Anonymous usage."),
                ),
        )

    private fun response(
        position: String = "bottom",
        theme: BannerTheme = BannerTheme(primaryColor = "#2563eb", background = "#ffffff", textColor = "#0f172a"),
        removeBranding: Boolean = false,
        consentLifetimeDays: Int = DEFAULT_CONSENT_LIFETIME_DAYS,
    ) = WidgetConfigResponse(
        siteKey = "pk_test",
        bannerVersion = 7,
        removeBranding = removeBranding,
        config =
            BannerConfigDocument(
                position = position,
                theme = theme,
                categories =
                    listOf(
                        BannerCategory(key = "necessary", required = true, enabledByDefault = true),
                        BannerCategory(key = "statistics", required = false, enabledByDefault = false),
                    ),
                languages = listOf("en", "de"),
                defaultLanguage = "en",
                texts = mapOf("en" to texts(), "de" to texts("Ihre Privatsphäre")),
                consentLifetimeDays = consentLifetimeDays,
            ),
    )

    @Test
    fun `hoists the stored document onto the flat shape the widget parses`() {
        val payload = WidgetConfigMapper.toPayload(response(removeBranding = true))

        assertEquals(7, payload.version)
        assertEquals("bottom", payload.position)
        assertEquals("en", payload.defaultLanguage)
        assertEquals(true, payload.removeBranding)
        // Theme tokens land under the widget's names, not the stored ones.
        assertEquals("#ffffff", payload.colors.background)
        assertEquals("#0f172a", payload.colors.text)
        assertEquals("#2563eb", payload.colors.button)
    }

    @Test
    fun `carries the site's consent lifetime through to the widget`() {
        // The widget stamps this into the consent cookie, so a dropped field would silently
        // re-prompt every visitor at 12 months regardless of what the customer configured.
        assertEquals(DEFAULT_CONSENT_LIFETIME_DAYS, WidgetConfigMapper.toPayload(response()).consentLifetimeDays)
        assertEquals(180, WidgetConfigMapper.toPayload(response(consentLifetimeDays = 180)).consentLifetimeDays)
    }

    @Test
    fun `renames category key to id and drops enabledByDefault`() {
        // The widget's own validation rejects a config whose categories lack a non-empty `id`,
        // falling back to defaults — so this rename is what makes the config usable at all.
        val payload = WidgetConfigMapper.toPayload(response())

        assertEquals(listOf("necessary", "statistics"), payload.categories.map { it.id })
        assertEquals(listOf(true, false), payload.categories.map { it.required })
    }

    @Test
    fun `renames description to message and keeps every offered language`() {
        val payload = WidgetConfigMapper.toPayload(response())

        assertEquals(setOf("en", "de"), payload.texts.keys)
        assertEquals("We use cookies to enhance your experience.", payload.texts["en"]?.message)
        assertEquals("Ihre Privatsphäre", payload.texts["de"]?.title)
        assertEquals("Save choices", payload.texts["en"]?.save)
    }

    @Test
    fun `maps the center position the widget cannot render onto bottom`() {
        assertEquals("bottom", WidgetConfigMapper.toPayload(response(position = "center")).position)
        assertEquals("top", WidgetConfigMapper.toPayload(response(position = "top")).position)
        // Defensive: a value that predates or bypasses the validator still renders somewhere sane.
        assertEquals("bottom", WidgetConfigMapper.toPayload(response(position = "sideways")).position)
    }

    @Test
    fun `derives a button label color that stays legible on the customer's primary color`() {
        // Dark button → white label; light button → black label.
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn("#2563eb"))
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn("#000000"))
        assertEquals("#000000", WidgetConfigMapper.readableTextOn("#ffffff"))
        assertEquals("#000000", WidgetConfigMapper.readableTextOn("#facc15"))
    }

    @Test
    fun `accepts hex shorthand and falls back to white for anything unparseable`() {
        assertEquals("#000000", WidgetConfigMapper.readableTextOn("#fff"))
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn("#123"))
        // Only reachable for rows written before the hex validator; must not throw.
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn("rebeccapurple"))
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn(""))
        assertEquals("#ffffff", WidgetConfigMapper.readableTextOn("#gggggg"))
    }

    @Test
    fun `passes the preferences panel copy through under the widget's names`() {
        val payload = WidgetConfigMapper.toPayload(response())

        val english = payload.texts["en"]
        assertEquals("Privacy preferences", english?.preferencesTitle)
        assertEquals("Close", english?.close)
        assertEquals("Always active", english?.alwaysActive)
        assertEquals("Strictly necessary", english?.categoryLabels?.get("necessary")?.label)
        assertEquals("Anonymous usage.", english?.categoryLabels?.get("statistics")?.description)
    }

    @Test
    fun `injects the attribution per language instead of reading it from the stored config`() {
        // Server-owned on purpose: suppressing it is a paid entitlement, so it must not be a field a
        // customer can blank out from the customizer.
        val payload = WidgetConfigMapper.toPayload(response())

        assertEquals("Powered by CookieKeeper", payload.texts["en"]?.poweredBy)
        assertEquals("Bereitgestellt von Complyr", payload.texts["de"]?.poweredBy)
        assertEquals("(opens in a new tab)", payload.texts["en"]?.opensInNewTab)
        assertEquals("(wird in einem neuen Tab geöffnet)", payload.texts["de"]?.opensInNewTab)
    }

    @Test
    fun `falls back to English attribution for a language we ship no copy for`() {
        val payload =
            WidgetConfigMapper.toPayload(
                response().let { it.copy(config = it.config.copy(texts = mapOf("pt" to texts()))) },
            )

        assertEquals("Powered by CookieKeeper", payload.texts["pt"]?.poweredBy)
    }

    @Test
    fun `carries the computed label color into the payload`() {
        val light = BannerTheme(primaryColor = "#ffe066", background = "#ffffff", textColor = "#0f172a")

        val payload = WidgetConfigMapper.toPayload(response(theme = light))

        assertEquals("#000000", payload.colors.buttonText)
        // The widget's four-token palette must be complete — a blank slot renders an unstyled banner.
        assertTrue(
            listOf(payload.colors.background, payload.colors.text, payload.colors.button).none { it.isBlank() },
        )
    }
}
