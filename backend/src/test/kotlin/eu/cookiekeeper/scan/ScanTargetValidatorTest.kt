package eu.cookiekeeper.scan

import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SSRF gate is the scanner's single most security-critical unit (ARCHITECTURE §4.4): a customer
 * controls the domain we resolve, so every private / reserved / link-local range must fail closed.
 * A fake resolver feeds fixed addresses so the range logic is exercised without real DNS. IP literals
 * are parsed by [InetAddress.getByName] (no lookup for a literal), so these are deterministic.
 */
class ScanTargetValidatorTest {
    /** Resolver backed by a fixed host->addresses map; unknown hosts throw like the system resolver. */
    private fun resolverOf(vararg entries: Pair<String, List<String>>): HostResolver {
        val map = entries.toMap()
        return HostResolver { host ->
            val ips = map[host] ?: throw UnknownHostException(host)
            ips.map { InetAddress.getByName(it) }.toTypedArray()
        }
    }

    private fun validatorFor(vararg entries: Pair<String, List<String>>) = ScanTargetValidator(resolverOf(*entries))

    @Test
    fun `validate accepts a domain resolving only to public addresses`() {
        val validator = validatorFor("shop.example.com" to listOf("93.184.216.34"))

        validator.validate("shop.example.com") // does not throw
    }

    @Test
    fun `validate rejects an unresolvable domain as a DNS failure`() {
        val validator = validatorFor() // empty map → every host is unknown

        val ex = assertFailsWithReason(ScanFailureReason.DNS_FAILURE) { validator.validate("nope.example.com") }
        assertEquals(ScanFailureReason.DNS_FAILURE, ex.reason)
    }

    @Test
    fun `validate rejects a domain resolving to no addresses`() {
        val validator = validatorFor("empty.example.com" to emptyList())

        assertFailsWithReason(ScanFailureReason.DNS_FAILURE) { validator.validate("empty.example.com") }
    }

    @Test
    fun `validate blocks every disallowed IPv4 range`() {
        val disallowed =
            mapOf(
                "loopback" to "127.0.0.1",
                "private-10" to "10.1.2.3",
                "private-172" to "172.16.5.4",
                "private-192" to "192.168.1.1",
                "link-local" to "169.254.169.254", // cloud metadata endpoint — the classic SSRF target
                "cgnat" to "100.64.0.1",
                "this-network" to "0.0.0.0",
                "reserved-240" to "240.0.0.1",
                "test-net" to "192.0.2.5",
                "benchmark" to "198.18.0.1",
            )
        disallowed.forEach { (label, ip) ->
            val validator = validatorFor("host" to listOf(ip))
            assertFailsWithReason(ScanFailureReason.BLOCKED_TARGET, "expected $label ($ip) to be blocked") {
                validator.validate("host")
            }
        }
    }

    @Test
    fun `validate blocks disallowed IPv6 ranges including mapped IPv4`() {
        val disallowed =
            mapOf(
                "v6-loopback" to "::1",
                "v6-link-local" to "fe80::1",
                "v6-ula" to "fc00::1",
                "v6-ula-fd" to "fd12:3456::1",
                "v6-unspecified" to "::",
                "v4-mapped-loopback" to "::ffff:127.0.0.1",
                "v4-mapped-private" to "::ffff:10.0.0.1",
            )
        disallowed.forEach { (label, ip) ->
            val validator = validatorFor("host" to listOf(ip))
            assertFailsWithReason(ScanFailureReason.BLOCKED_TARGET, "expected $label ($ip) to be blocked") {
                validator.validate("host")
            }
        }
    }

    @Test
    fun `validate blocks when any one of several addresses is disallowed`() {
        // A DNS-rebinding-style answer that mixes a public and a private A record must fail closed.
        val validator = validatorFor("mixed.example.com" to listOf("93.184.216.34", "10.0.0.5"))

        assertFailsWithReason(ScanFailureReason.BLOCKED_TARGET) { validator.validate("mixed.example.com") }
    }

    @Test
    fun `isPublicHost is true only when every address resolves public`() {
        val validator =
            validatorFor(
                "public.example.com" to listOf("93.184.216.34"),
                "internal.example.com" to listOf("10.0.0.9"),
                "mixed.example.com" to listOf("93.184.216.34", "127.0.0.1"),
            )

        assertTrue(validator.isPublicHost("public.example.com"))
        assertFalse(validator.isPublicHost("internal.example.com"))
        assertFalse(validator.isPublicHost("mixed.example.com"), "any private address must make it non-public")
        assertFalse(validator.isPublicHost("unknown.example.com"), "an unresolvable host is not public")
    }

    @Test
    fun `isDisallowedIpLiteral flags private literals without touching DNS`() {
        // Resolver throws for any lookup, proving these are pure literal parses (a lookup would throw).
        val validator = ScanTargetValidator { throw UnknownHostException("no DNS allowed in this test") }

        assertTrue(validator.isDisallowedIpLiteral("127.0.0.1"))
        assertTrue(validator.isDisallowedIpLiteral("10.0.0.1"))
        assertTrue(validator.isDisallowedIpLiteral("169.254.169.254"))
        assertTrue(validator.isDisallowedIpLiteral("::1"))
        assertTrue(validator.isDisallowedIpLiteral("[::1]"))
        assertTrue(validator.isDisallowedIpLiteral("fc00::1"))

        assertFalse(validator.isDisallowedIpLiteral("93.184.216.34"), "a public literal is allowed")
        assertFalse(validator.isDisallowedIpLiteral("example.com"), "a hostname is not a literal — no DNS here")
    }

    @Test
    fun `isDisallowedIpLiteral blocks alternate IPv4 encodings of internal addresses`() {
        // Chromium normalizes these to 127.0.0.1 / 169.254.169.254; the guard must too, or a
        // sub-resource like <img src="http://2130706433/"> reaches loopback/metadata. Resolver throws
        // to prove the block is a pure literal parse, never a DNS lookup.
        val validator = ScanTargetValidator { throw UnknownHostException("no DNS allowed in this test") }

        assertTrue(validator.isDisallowedIpLiteral("2130706433"), "decimal 127.0.0.1")
        assertTrue(validator.isDisallowedIpLiteral("0x7f.0.0.1"), "hex first octet")
        assertTrue(validator.isDisallowedIpLiteral("0177.0.0.1"), "octal first octet")
        assertTrue(validator.isDisallowedIpLiteral("127.1"), "short form")
        assertTrue(validator.isDisallowedIpLiteral("0x7f000001"), "single hex word")
        assertTrue(validator.isDisallowedIpLiteral("2852039166"), "decimal 169.254.169.254")
        assertTrue(validator.isDisallowedIpLiteral("[::ffff:a9fe:a9fe]"), "v4-mapped metadata address")
    }

    @Test
    fun `isDisallowedIpLiteral allows public literals in alternate forms and rejects malformed IPv6`() {
        val validator = ScanTargetValidator { throw UnknownHostException("no DNS allowed in this test") }

        assertFalse(validator.isDisallowedIpLiteral("2606:2800:220:1:248:1893:25c8:1946"), "a public IPv6 literal")
        assertFalse(validator.isDisallowedIpLiteral("0x5db8d822"), "hex form of a public IPv4 (93.184.216.34)")
        assertFalse(validator.isDisallowedIpLiteral("256.100.50.25"), "an out-of-range part is not a literal")
        assertTrue(validator.isDisallowedIpLiteral("::gg"), "a malformed IPv6 literal fails closed")
    }

    private fun assertFailsWithReason(
        expected: ScanFailureReason,
        message: String? = null,
        block: () -> Unit,
    ): ScanTargetException {
        val ex =
            try {
                block()
                throw AssertionError(message ?: "expected ScanTargetException($expected) but nothing was thrown")
            } catch (thrown: ScanTargetException) {
                thrown
            }
        assertEquals(expected, ex.reason, message ?: "wrong reason")
        return ex
    }
}
