package eu.cookiekeeper.policy

import eu.cookiekeeper.banner.ConsentCategory
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyRendererTest {
    private val updatedOn = LocalDate.parse("2026-08-01")

    private fun context(
        companyName: String = "Acme GmbH",
        contactEmail: String = "privacy@acme.example",
        websiteUrl: String = "https://acme.example",
        address: String? = null,
        cookiesByCategory: Map<String, List<PolicyCookie>> = emptyMap(),
        unclassified: List<PolicyCookie> = emptyList(),
    ) = PolicyContext(
        companyName = companyName,
        contactEmail = contactEmail,
        websiteUrl = websiteUrl,
        address = address,
        updatedOn = updatedOn,
        cookiesByCategory = cookiesByCategory,
        unclassified = unclassified,
    )

    @Test
    fun `renders the localized title and business details for each language`() {
        val titles =
            mapOf(
                "en" to "Cookie Policy",
                "de" to "Cookie-Richtlinie",
                "fr" to "Politique relative aux cookies",
                "es" to "Política de cookies",
                "it" to "Informativa sui cookie",
            )
        titles.forEach { (lang, title) ->
            val html = PolicyRenderer.render(lang, context())
            assertContains(html, "<h1>$title</h1>", message = "title missing for $lang")
            assertContains(html, "Acme GmbH", message = "company missing for $lang")
            assertContains(html, "privacy@acme.example", message = "contact missing for $lang")
            assertContains(html, "lang=\"$lang\"")
        }
    }

    @Test
    fun `escapes attacker-influenced values so scanned cookie data cannot inject markup`() {
        val html =
            PolicyRenderer.render(
                "en",
                context(
                    companyName = "<script>alert(1)</script>",
                    cookiesByCategory =
                        mapOf(
                            ConsentCategory.STATISTICS.key to
                                listOf(PolicyCookie(name = "_ga\"><img src=x>", provider = "Evil & Co", expiry = "1 year", domain = null)),
                        ),
                ),
            )

        assertFalse(html.contains("<script>alert(1)</script>"), "raw script tag must not survive")
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;")
        assertContains(html, "_ga&quot;&gt;&lt;img src=x&gt;")
        assertContains(html, "Evil &amp; Co")
    }

    @Test
    fun `groups cookies under their category in canonical order`() {
        val html =
            PolicyRenderer.render(
                "en",
                context(
                    cookiesByCategory =
                        mapOf(
                            ConsentCategory.MARKETING.key to listOf(PolicyCookie("_fbp", "Meta", "3 months", null)),
                            ConsentCategory.NECESSARY.key to listOf(PolicyCookie("session", null, null, null)),
                        ),
                ),
            )

        assertContains(html, "Strictly necessary cookies")
        assertContains(html, "Marketing cookies")
        // Necessary section must appear before Marketing (canonical ConsentCategory order).
        assertTrue(
            html.indexOf("Strictly necessary cookies") < html.indexOf("Marketing cookies"),
            "categories out of canonical order",
        )
        // A cookie with no provider/expiry falls back to the localized session/unknown labels.
        assertContains(html, "<td>session</td><td>—</td><td>Session</td>")
    }

    @Test
    fun `lists unclassified cookies in their own trailing section`() {
        val html =
            PolicyRenderer.render(
                "en",
                context(
                    cookiesByCategory = mapOf(ConsentCategory.NECESSARY.key to listOf(PolicyCookie("session", null, null, null))),
                    unclassified = listOf(PolicyCookie("mystery", null, null, "cdn.example")),
                ),
            )

        assertContains(html, "Other cookies")
        assertTrue(html.indexOf("Strictly necessary cookies") < html.indexOf("Other cookies"))
        // Provider column falls back to the cookie's domain when no provider is known.
        assertContains(html, "<td>mystery</td><td>cdn.example</td>")
    }

    @Test
    fun `states explicitly when no cookies were detected`() {
        val html = PolicyRenderer.render("en", context())

        assertContains(html, "found no cookies")
        assertFalse(html.contains("<table"), "no table should render when there are no cookies")
    }

    @Test
    fun `includes the postal address only when provided`() {
        assertFalse(PolicyRenderer.render("en", context(address = null)).contains("Postal address"))
        assertFalse(PolicyRenderer.render("en", context(address = "  ")).contains("Postal address"))
        assertContains(PolicyRenderer.render("en", context(address = "1 Main St")), "Postal address: 1 Main St")
    }

    @Test
    fun `a placeholder literal inside a business value is not re-substituted`() {
        // A company name that itself contains the website placeholder must stay verbatim (escaped),
        // never swapped for the website value — the single-pass fill guarantees this.
        val html = PolicyRenderer.render("en", context(companyName = "{website} Ltd", websiteUrl = "https://acme.example"))

        assertContains(html, "{website} Ltd")
        // The real {website} slot is still filled exactly once.
        assertContains(html, "https://acme.example")
    }

    @Test
    fun `unsupported language falls back to the default bundle`() {
        assertEquals(
            PolicyRenderer.render("en", context()),
            PolicyRenderer.render("xx", context()),
        )
    }
}
