package com.complyr.site

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainValidatorTest {
    @ParameterizedTest(name = "normalizes `{0}` to `{1}`")
    @CsvSource(
        "example.com, example.com",
        "EXAMPLE.COM, example.com",
        "' example.com ', example.com",
        "HTTPS://Foo.Example.COM/path, foo.example.com",
        "http://example.com, example.com",
        "https://example.com/, example.com",
        "example.com:8080, example.com",
        "https://example.com:443/shop?q=1#top, example.com",
        "example.com., example.com",
        "www.example.co.uk, www.example.co.uk",
        "sub.sub2.example.com, sub.sub2.example.com",
        "xn--mnchen-3ya.de, xn--mnchen-3ya.de",
        "123.example.com, 123.example.com",
        "a-b.example.com, a-b.example.com",
    )
    fun `normalizes valid input`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, DomainValidator.normalize(input))
    }

    @Test
    fun `IDN domains are converted to punycode`() {
        assertEquals("xn--mnchen-3ya.de", DomainValidator.normalize("münchen.de"))
        assertEquals("xn--mnchen-3ya.de", DomainValidator.normalize("MÜNCHEN.de"))
        assertEquals("xn--caf-dma.example.com", DomainValidator.normalize("café.example.com"))
    }

    @ParameterizedTest(name = "rejects `{0}`")
    @ValueSource(
        strings = [
            "",
            "   ",
            "localhost",
            "LOCALHOST",
            "intranet",
            "192.168.0.1",
            "10.0.0.1",
            "8.8.8.8",
            "http://192.168.0.1/admin",
            "[::1]",
            "2001:db8::1",
            "-bad.example.com",
            "bad-.example.com",
            "exa mple.com",
            "under_score.example.com",
            "https://",
            "http:///path",
            "example..com",
            ".example.com",
        ],
    )
    fun `rejects invalid input`(input: String) {
        assertThrows<InvalidDomainException> { DomainValidator.normalize(input) }
    }

    @Test
    fun `rejects overlong domains and labels`() {
        val longLabel = "a".repeat(64)
        assertThrows<InvalidDomainException> { DomainValidator.normalize("$longLabel.com") }

        val longDomain = (1..64).joinToString(separator = ".") { "abc" } // 255 chars
        assertThrows<InvalidDomainException> { DomainValidator.normalize(longDomain) }
    }
}
