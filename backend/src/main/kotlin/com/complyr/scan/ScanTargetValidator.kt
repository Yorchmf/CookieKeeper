package com.complyr.scan

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A crawl target was refused before (or during) the crawl. Carries a customer-safe [reason] code so
 * the worker can record it on the scan row without leaking the offending host/IP into the dashboard
 * or audit log (GDPR); the human message is for server logs only.
 */
class ScanTargetException(
    val reason: ScanFailureReason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Resolves a hostname to its IP addresses. Extracted behind an interface purely so the SSRF range
 * logic in [ScanTargetValidator] is unit-testable with fixed addresses (no real DNS in tests).
 */
fun interface HostResolver {
    /** Resolve [host] to one or more addresses, or throw [UnknownHostException] if it cannot resolve. */
    fun resolve(host: String): Array<InetAddress>
}

/** Production resolver: the JVM's system resolver (respects the container's DNS + `/etc/hosts`). */
object SystemHostResolver : HostResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

/**
 * SSRF gate for the scanner (ARCHITECTURE §4.4): the crawler must "resolve DNS and reject
 * private/link-local ranges" so a customer-controlled domain can never point us at internal
 * infrastructure (cloud metadata endpoints, the DB, other tenants, the Docker gateway).
 *
 * This is only the DNS/range layer. The authoritative SSRF backstop is the scanner's network
 * isolation (its container has no inbound ports and — per the deploy posture — no route to internal
 * services), because a resolve-here / connect-in-Chromium gap leaves a residual DNS-rebinding window
 * this class cannot close on its own. [validate] is the up-front check; [isPublicHost] /
 * [isDisallowedIpLiteral] are the per-request checks the crawler's route handler layers on top.
 */
@Component
class ScanTargetValidator(
    private val resolver: HostResolver = SystemHostResolver,
) {
    private val log = LoggerFactory.getLogger(ScanTargetValidator::class.java)

    /**
     * Resolve [host] and fail closed unless every returned address is publicly routable.
     * Throws [ScanTargetException] with [ScanFailureReason.DNS_FAILURE] when it will not resolve and
     * [ScanFailureReason.BLOCKED_TARGET] when any address falls in a disallowed range.
     */
    fun validate(host: String) {
        val addresses = resolveOrThrow(host)
        val blocked = addresses.firstOrNull(::isDisallowed)
        if (blocked != null) {
            // The offending IP is logged server-side only; the customer-facing reason stays generic.
            log.warn("Refusing scan target {} — resolves to disallowed address {}", host, blocked.hostAddress)
            throw ScanTargetException(ScanFailureReason.BLOCKED_TARGET, "target $host resolves to a non-public address")
        }
    }

    /**
     * True only if [host] resolves and *all* of its addresses are publicly routable. Never throws —
     * used by the crawler's per-navigation route handler, where a resolve failure or any disallowed
     * address must simply block the request (fail closed), not surface an error.
     */
    fun isPublicHost(host: String): Boolean {
        val addresses = runCatching { resolver.resolve(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.none(::isDisallowed)
    }

    /**
     * Cheap, DNS-free guard for sub-resource requests: true if [host] is an IP *literal* in a
     * disallowed range. Hostnames (which require DNS to resolve) return false here — they are handled
     * by the navigation path / container isolation, not by resolving every third-party asset request.
     *
     * IPv4 is parsed with the WHATWG/Chromium rules (decimal, `0`-octal and `0x`-hex parts, 1–4
     * groups) so alternate encodings of an internal address — `2130706433`, `0x7f.0.0.1`, `0177.0.0.1`,
     * `127.1` — are all recognized and blocked, closing the parser differential with the browser that
     * actually connects. A colon-bearing host is an IPv6 literal (colons never appear in a hostname);
     * one that will not parse fails closed.
     */
    fun isDisallowedIpLiteral(host: String): Boolean {
        val address = ipLiteralAddress(host) ?: return false
        return isDisallowed(address)
    }

    /** Parse [host] as an IP literal without DNS; null if it is not a literal (a hostname). */
    private fun ipLiteralAddress(host: String): InetAddress? {
        val candidate = host.removeSurrounding("[", "]")
        // A colon host is always an IPv6 literal (colons never appear in a hostname).
        return if (candidate.contains(':')) {
            ipv6LiteralAddress(candidate)
        } else {
            parseWhatwgIpv4(candidate)?.let(InetAddress::getByAddress)
        }
    }

    /** Parse an IPv6 literal without DNS; a malformed one yields loopback so it fails closed (blocked). */
    private fun ipv6LiteralAddress(candidate: String): InetAddress =
        runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: InetAddress.getLoopbackAddress()

    private fun resolveOrThrow(host: String): Array<InetAddress> {
        val addresses =
            try {
                resolver.resolve(host)
            } catch (ex: UnknownHostException) {
                throw ScanTargetException(ScanFailureReason.DNS_FAILURE, "target $host does not resolve", ex)
            }
        if (addresses.isEmpty()) {
            throw ScanTargetException(ScanFailureReason.DNS_FAILURE, "target $host resolved to no addresses")
        }
        return addresses
    }

    /** Whether [address] is anything other than a globally-routable public unicast address. */
    private fun isDisallowed(address: InetAddress): Boolean {
        if (isJvmNonRoutable(address)) return true
        return when (address) {
            is Inet4Address -> isDisallowedIpv4(address.address)
            is Inet6Address -> isDisallowedIpv6(address.address)
            else -> true // unknown address family — refuse rather than guess
        }
    }

    /** The ranges the JVM already classifies: any-local, loopback, link-local, site-local, multicast. */
    private fun isJvmNonRoutable(address: InetAddress): Boolean =
        listOf(
            address.isAnyLocalAddress, // 0.0.0.0, ::
            address.isLoopbackAddress, // 127.0.0.0/8, ::1
            address.isLinkLocalAddress, // 169.254.0.0/16, fe80::/10
            address.isSiteLocalAddress, // 10/8, 172.16/12, 192.168/16
            address.isMulticastAddress, // 224.0.0.0/4, ff00::/8
        ).any { it }

    /** IPv4 ranges the JVM's built-in predicates miss (CGNAT, this-network, reserved/benchmark). */
    private fun isDisallowedIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and OCTET_MASK
        val second = bytes[1].toInt() and OCTET_MASK
        return when {
            first == THIS_NETWORK_FIRST -> true // 0.0.0.0/8 "this network"
            first == CGNAT_FIRST && second in CGNAT_SECOND_RANGE -> true // 100.64.0.0/10 carrier-grade NAT
            first == LINK_LOCAL_FIRST && second == LINK_LOCAL_SECOND -> true // 169.254/16 (defensive)
            first == IETF_FIRST && second == IETF_SECOND -> true // 192.0.0.0/24 IETF + 192.0.2.0/24 TEST-NET-1
            first == BENCHMARK_FIRST && second in BENCHMARK_SECOND_RANGE -> true // 198.18.0.0/15 benchmarking
            first >= RESERVED_FIRST_MIN -> true // 240.0.0.0/4 reserved + 255.255.255.255 broadcast
            else -> false
        }
    }

    /** IPv6 ranges beyond the JVM predicates: unique-local (ULA). IPv4-mapped forms arrive as Inet4Address. */
    private fun isDisallowedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and OCTET_MASK
        // fc00::/7 unique local addresses (isSiteLocalAddress only matches the deprecated fec0::/10).
        return first and ULA_PREFIX_MASK == ULA_PREFIX
    }

    /**
     * Parse [host] as an IPv4 address the way the WHATWG URL parser (and thus Chromium) does — 1–4
     * dot-separated parts, each decimal, `0`-prefixed octal, or `0x`-prefixed hex, the final part
     * spanning the remaining octets. Returns the 4 network-order bytes, or null when any part is not
     * a number (i.e. an ordinary hostname) so the caller treats it as a name, never touching DNS.
     */
    private fun parseWhatwgIpv4(host: String): ByteArray? {
        val rawParts = host.split('.')
        // A single trailing dot ("127.0.0.1.") is tolerated, matching the URL parser.
        val parts = if (rawParts.size > 1 && rawParts.last().isEmpty()) rawParts.dropLast(1) else rawParts
        val numbers = parts.map { parseIpv4Part(it) }
        return when {
            host.isEmpty() || parts.isEmpty() || parts.size > IPV4_MAX_PARTS -> null
            numbers.any { it == null } -> null // any non-numeric part → a hostname, not a literal
            else -> combineIpv4Numbers(numbers.filterNotNull())
        }
    }

    /** Fold 1–4 parsed IPv4 parts into 4 network-order bytes (final part spans the remaining octets). */
    private fun combineIpv4Numbers(numbers: List<Long>): ByteArray? {
        val leading = numbers.dropLast(1)
        val remainingOctets = IPV4_MAX_PARTS - leading.size
        val last = numbers.last()
        return when {
            leading.any { it > OCTET_MASK } -> null // every part but the last is a single octet
            last >= (1L shl (BITS_PER_OCTET * remainingOctets)) -> null
            else -> ipv4Bytes(leading, last)
        }
    }

    /** Assemble the 4 network-order bytes from the leading single octets and the final span. */
    private fun ipv4Bytes(
        leading: List<Long>,
        last: Long,
    ): ByteArray {
        var address = last
        leading.forEachIndexed { index, octet ->
            address = address or (octet shl (BITS_PER_OCTET * (IPV4_MAX_PARTS - 1 - index)))
        }
        return ByteArray(IPV4_MAX_PARTS) { i ->
            (address ushr (BITS_PER_OCTET * (IPV4_MAX_PARTS - 1 - i)) and OCTET_MASK.toLong()).toByte()
        }
    }

    /** One dotted part of an IPv4 literal, in the radix its prefix implies. Null if it is not a number. */
    private fun parseIpv4Part(part: String): Long? {
        if (part.isEmpty()) return null
        val (radix, digits) =
            when {
                part.startsWith(HEX_PREFIX, ignoreCase = true) -> HEX_RADIX to part.substring(HEX_PREFIX.length)
                part.length > 1 && part[0] == '0' -> OCTAL_RADIX to part.substring(1)
                else -> DECIMAL_RADIX to part
            }
        val value = if (digits.isEmpty()) 0L else digits.toLongOrNull(radix) ?: return null
        return value.takeIf { it in 0L..MAX_IPV4_ADDRESS }
    }

    private companion object {
        // Low byte of an octet / IPv6 prefix nibble math.
        const val OCTET_MASK = 0xFF
        const val ULA_PREFIX_MASK = 0xFE
        const val ULA_PREFIX = 0xFC

        // IPv4 range boundaries (first / second octet) the JVM predicates don't cover.
        const val THIS_NETWORK_FIRST = 0
        const val CGNAT_FIRST = 100
        val CGNAT_SECOND_RANGE = 64..127
        const val LINK_LOCAL_FIRST = 169
        const val LINK_LOCAL_SECOND = 254
        const val IETF_FIRST = 192
        const val IETF_SECOND = 0
        const val BENCHMARK_FIRST = 198
        val BENCHMARK_SECOND_RANGE = 18..19
        const val RESERVED_FIRST_MIN = 240

        // WHATWG IPv4 literal parsing (decimal / 0-octal / 0x-hex parts).
        const val IPV4_MAX_PARTS = 4
        const val BITS_PER_OCTET = 8
        const val HEX_PREFIX = "0x"
        const val HEX_RADIX = 16
        const val OCTAL_RADIX = 8
        const val DECIMAL_RADIX = 10
        const val MAX_IPV4_ADDRESS = 0xFFFFFFFFL
    }
}
