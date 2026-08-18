package eu.cookiekeeper.support

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactEmailComposerTest {
    private val composer = ContactEmailComposer()

    @Test
    fun `uses a fixed subject header carrying no user input`() {
        val composed = composer.compose("alice@example.com", "en", "Anything <script>", "Hi")

        assertEquals("New CookieKeeper contact-form message", composed.subject)
    }

    @Test
    fun `escapes the subject and message so a customer cannot inject markup into the support email`() {
        val composed =
            composer.compose(
                email = "alice@example.com",
                locale = "en",
                subject = "<b>bold</b>",
                message = "<img src=x onerror=alert(1)>",
            )

        assertTrue(composed.htmlBody.contains("&lt;b&gt;bold&lt;/b&gt;"))
        assertTrue(composed.htmlBody.contains("&lt;img src=x onerror=alert(1)&gt;"))
        // The raw tags must never survive into the rendered body.
        assertFalse(composed.htmlBody.contains("<b>bold</b>"))
        assertFalse(composed.htmlBody.contains("<img src=x"))
    }

    @Test
    fun `escapes the echoed account email and locale`() {
        val composed = composer.compose("<a>@x", "e<n", "Subject", "Body")

        assertTrue(composed.htmlBody.contains("&lt;a&gt;@x"))
        assertTrue(composed.htmlBody.contains("e&lt;n"))
    }

    @Test
    fun `renders message newlines as line breaks after escaping`() {
        val composed = composer.compose("alice@example.com", "en", "Subject", "line one\nline two")

        assertTrue(composed.htmlBody.contains("line one<br>line two"))
    }

    @Test
    fun `does not turn an escaped-then-broken message into a smuggled tag`() {
        // A newline adjacent to text must not let an escaped '<' recombine into a live tag.
        val composed = composer.compose("alice@example.com", "en", "Subject", "<script>\nalert(1)")

        assertTrue(composed.htmlBody.contains("&lt;script&gt;<br>alert(1)"))
        assertFalse(composed.htmlBody.contains("<script>"))
    }
}
