package com.complyr.site

import com.complyr.common.ComplyrProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

/**
 * The host of `complyr.cdn-base-url`, resolved once at startup — the value [SnippetMatcher] compares a
 * customer's `<script src>` against when deciding whether our widget is really installed (ADR-17).
 *
 * Resolved with [URI] rather than string-chopped because "the host" and "the text after `https://`" are
 * different things: `https://cdn.complyr.eu@evil.example.com/` has a *host* of `evil.example.com`. The
 * matcher already parses the customer's side that way, and the two must agree or the comparison means
 * nothing.
 *
 * **Why a misconfiguration fails startup instead of degrading.** `cdn-base-url` defaults to
 * `http://localhost:8081` for local development. Deployed with that default, verification would compare
 * every customer's snippet against the host `localhost`, match nothing, and report `snippet_not_found`
 * to customers who have installed the snippet correctly — a silent, self-inflicted outage of the
 * activation step, indistinguishable from the normal not-installed-yet answer. There is no configuration
 * of a production CDN for which a loopback host is correct, so `prd` refuses to boot on one.
 */
@Component
class CdnHost(
    properties: ComplyrProperties,
    environment: Environment,
) {
    /** The lowercased host, e.g. `cdn.complyr.eu`. */
    val value: String = resolve(properties.cdnBaseUrl, isProduction = PRODUCTION_PROFILE in environment.activeProfiles)

    private fun resolve(
        cdnBaseUrl: String,
        isProduction: Boolean,
    ): String {
        val host =
            runCatching { URI(cdnBaseUrl).host }
                .getOrNull()
                ?.lowercase()
                ?.removeSuffix(".")
        require(!host.isNullOrEmpty()) {
            "complyr.cdn-base-url (CDN_BASE_URL) must be an absolute URL with a host (was '$cdnBaseUrl')"
        }
        require(!isProduction || host !in LOOPBACK_HOSTS) {
            "complyr.cdn-base-url (CDN_BASE_URL) still points at '$host' in the $PRODUCTION_PROFILE profile; " +
                "domain verification would never match a customer's snippet"
        }
        return host
    }

    private companion object {
        const val PRODUCTION_PROFILE = "prd"
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
}
