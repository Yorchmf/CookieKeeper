package com.complyr.site

import com.complyr.common.ComplyrProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Hashtable
import javax.naming.Context
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext

/**
 * The DNS-TXT half of domain verification (ADR-17): a `_complyr.{domain}` TXT record whose value is the
 * site key proves control of the domain's zone. It is the fallback for customers who cannot (or will
 * not) install the widget snippet first — the same bar ACME's DNS-01 challenge sets for TLS issuance.
 *
 * It proves *zone* control, not *content* control. Accepted, and recorded in ADR-17: anyone who can
 * publish records for a domain can also point it anywhere, so the distinction buys an attacker nothing.
 *
 * **On JNDI.** The JNDI RCE family (Log4Shell et al.) comes from `ldap:`/`rmi:` contexts resolving a
 * remote *reference* through an object factory, which loads and instantiates attacker-supplied classes.
 * Two independent facts keep that machinery out of reach here, and it is worth being precise about which
 * ones actually do the work:
 *
 *  1. `DnsContext.c_getAttributes` never calls `DirectoryManager.getObjectInstance`. Object factories in
 *     the DNS provider are reachable only from `c_lookup` and `BindingEnumeration.next`, neither of which
 *     is on this path. TXT rdata is decoded straight to a `String`. So no DNS answer, however hostile,
 *     can reach a factory, a deserializer, or a remote codebase URL.
 *  2. The *name* — not [Context.PROVIDER_URL] — is what selects a URL context factory:
 *     `InitialContext.getURLOrDefaultInitCtx` reads the scheme off the name being looked up and
 *     dispatches on that, bypassing both `INITIAL_CONTEXT_FACTORY` and `PROVIDER_URL`. What protects us
 *     is the constant `_complyr.` prefix: any scheme an attacker tried to smuggle in would have to be
 *     `_complyr.<x>`, which resolves to an unloadable factory class name and falls back to the DNS
 *     context. [normalizedName] then makes this belt-and-braces by rejecting anything
 *     [DomainValidator] would not accept, so `/`, `:`, whitespace and control bytes never get that far.
 *
 * That second point is why [hasSiteKeyRecord] validates its own input rather than trusting the caller:
 * this is a public component whose safety argument must not depend on where it happens to be called from.
 *
 * No new dependency: `jdk.naming.dns` ships with the JDK, and `backend/Dockerfile` runs the full
 * `eclipse-temurin:21-jre` module set.
 */
@Component
class DnsTxtLookup(
    private val properties: ComplyrProperties,
    private val resolver: TxtResolver = JndiTxtResolver,
) {
    private val log = LoggerFactory.getLogger(DnsTxtLookup::class.java)

    /**
     * True when `_complyr.{domain}` publishes a TXT record equal to [siteKey]. Any failure — NXDOMAIN,
     * timeout, a broken resolver, a domain that isn't one — is a plain false: the caller must not be able
     * to tell the difference (see [SiteVerificationFetcher] on why verification reports one
     * undifferentiated outcome).
     *
     * Suppressions: `ReturnCount` because each early return is a distinct fail-closed condition, and
     * `TooGenericExceptionCaught` for the reason spelled out at the second catch block.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun hasSiteKeyRecord(
        domain: String,
        siteKey: String,
    ): Boolean {
        // A blank key would match an empty TXT record (`_complyr.victim.com. IN TXT ""` decodes to `""`),
        // i.e. fail open on the one input that must never verify anything.
        if (siteKey.isBlank()) return false
        val name = normalizedName(domain) ?: return false
        val records =
            try {
                resolver.lookupTxt(name, properties.verification.dnsTimeout.toMillis())
            } catch (ex: NamingException) {
                // NXDOMAIN and "no TXT records" both land here and are entirely normal.
                log.debug("TXT lookup for {} failed: {}", name, ex.javaClass.simpleName)
                return false
            } catch (ex: Exception) {
                // `@Throws` is documentation only in Kotlin, and the JDK provider itself can throw
                // unchecked (a bad timeout value reaches an Integer.parseInt inside the context
                // constructor). Letting that escape would 500 where every other outcome is a quiet miss —
                // the single distinguishable response this whole design exists to avoid.
                log.warn("TXT lookup for {} failed unexpectedly: {}", name, ex.javaClass.simpleName)
                return false
            }
        return records.any { normalize(it) == siteKey }
    }

    /**
     * `_complyr.{domain}`, or null if [domain] is not a domain. Re-normalizing here rather than trusting
     * the caller is the enforcement behind the class KDoc's JNDI argument: `DnsName.verifyLabel` checks
     * only label *length*, so an unvalidated string would be emitted verbatim as a DNS query name —
     * spaces, `*`, control bytes and `\DDD` escapes included — and a `/` would re-parse the whole thing as
     * a multi-component `CompositeName`. That is a query-name injection and an exfiltration channel to an
     * attacker-run authoritative server, well short of RCE but not something to leave to convention.
     */
    private fun normalizedName(domain: String): String? =
        runCatching { DomainValidator.normalize(domain) }
            .getOrNull()
            ?.let { "$RECORD_PREFIX.$it" }

    /**
     * Reassembles a TXT value into the form a publisher meant. A value longer than 255 bytes is split by
     * the protocol into chunks, which the JDK rejoins with a space (`pk_ AbC123`), and it quotes a chunk
     * when — and only when — that chunk is empty or contains a space, `"` or `\`. Dropping quotes and
     * whitespace covers both. A site key is `pk_` + alphanumerics and so contains neither, meaning
     * nothing a legitimate record could carry is lost.
     */
    private fun normalize(record: String): String = record.filterNot { it == '"' || it.isWhitespace() }

    /**
     * The DNS seam. Mirrors [com.complyr.scan.ScanTargetValidator]'s `HostResolver`: production binds the
     * JNDI implementation by default parameter, tests pass a fake and never touch the network.
     */
    fun interface TxtResolver {
        /** TXT values for [name], or an empty list when there are none. Throws only on resolver failure. */
        @Throws(NamingException::class)
        fun lookupTxt(
            name: String,
            timeoutMillis: Long,
        ): List<String>
    }

    /** Production resolver: the JDK's DNS context, bounded by an explicit timeout and a single retry. */
    object JndiTxtResolver : TxtResolver {
        override fun lookupTxt(
            name: String,
            timeoutMillis: Long,
        ): List<String> {
            // Hashtable, not a Map: the JNDI context constructor predates the collections framework.
            val environment =
                Hashtable<String, String>().apply {
                    put(Context.INITIAL_CONTEXT_FACTORY, DNS_CONTEXT_FACTORY)
                    // Constant, never derived from input — see the class KDoc's JNDI safety argument.
                    put(Context.PROVIDER_URL, "dns:")
                    put("com.sun.jndi.dns.timeout.initial", timeoutMillis.toString())
                    put("com.sun.jndi.dns.timeout.retries", "1")
                }
            val context = InitialDirContext(environment)
            return try {
                val attribute = context.getAttributes(name, arrayOf(TXT_ATTRIBUTE)).get(TXT_ATTRIBUTE)
                attribute
                    ?.all
                    ?.asSequence()
                    ?.mapNotNull { it?.toString() }
                    ?.toList() ?: emptyList()
            } finally {
                context.close()
            }
        }

        private const val DNS_CONTEXT_FACTORY = "com.sun.jndi.dns.DnsContextFactory"
        private const val TXT_ATTRIBUTE = "TXT"
    }

    companion object {
        /** The record name the dashboard tells customers to create: `_complyr.{their domain}`. */
        const val RECORD_PREFIX = "_complyr"

        // There is deliberately no cap on the number of records examined. An earlier `take(20)` bounded
        // nothing — the resolver has already materialized the whole list by then — while making
        // verification order-dependent, so a zone with more than 20 TXT records at this name would verify
        // intermittently as the resolver rotated the RRset. A DNS answer is capped at 64KB by the
        // protocol, which is the real bound.
    }
}
