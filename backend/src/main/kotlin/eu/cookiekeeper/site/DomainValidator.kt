package eu.cookiekeeper.site

import java.net.IDN

/**
 * Pure normalization/validation of customer-entered domains.
 *
 * Accepts messy input (`HTTPS://Foo.Example.COM/path`), returns a bare lowercase
 * punycode hostname (`foo.example.com`), and rejects anything that is not a public
 * multi-label DNS name (IP literals, localhost, single labels, bad charsets).
 */
object DomainValidator {
    private const val MAX_DOMAIN_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private val LABEL_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
    private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun normalize(input: String): String {
        val host = extractHost(input.trim().lowercase())
        val ascii = toAscii(host)
        validateShape(ascii)
        validateLabels(ascii)
        return ascii
    }

    private fun extractHost(trimmed: String): String {
        if (trimmed.isEmpty()) throw InvalidDomainException()
        val withoutScheme = trimmed.substringAfter("://")
        val hostAndPort =
            withoutScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        // IPv6 literals ([::1]) and anything with multiple colons are not domains.
        if (hostAndPort.startsWith("[") || hostAndPort.count { it == ':' } > 1) throw InvalidDomainException()
        return hostAndPort.substringBefore(':').removeSuffix(".")
    }

    private fun toAscii(host: String): String {
        if (host.isEmpty()) throw InvalidDomainException()
        return try {
            IDN.toASCII(host)
        } catch (_: IllegalArgumentException) {
            throw InvalidDomainException()
        }
    }

    private fun validateShape(ascii: String) {
        val invalid =
            ascii.isEmpty() ||
                ascii.length > MAX_DOMAIN_LENGTH ||
                '.' !in ascii ||
                // single label (incl. "localhost")
                IPV4_REGEX.matches(ascii)
        if (invalid) throw InvalidDomainException()
    }

    private fun validateLabels(ascii: String) {
        val labels = ascii.split('.')
        val allValid =
            labels.all { label ->
                label.length in 1..MAX_LABEL_LENGTH && LABEL_REGEX.matches(label)
            }
        if (!allValid) throw InvalidDomainException()
    }
}
