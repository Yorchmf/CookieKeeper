package com.complyr.consent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stateless HMAC origin token in isolation: a round-tripped token verifies, and every way it can
 * be tampered with, replayed late, or mismatched against the request is rejected. These are the
 * guarantees the consent endpoint leans on to reject a present-but-invalid token (see [ConsentOriginToken]).
 */
class ConsentOriginTokenTest {
    private val now: Instant = Instant.parse("2026-07-30T12:00:00Z")
    private val secret = "test-only-consent-origin-token-secret-0123456789"
    private val ttl: Duration = Duration.ofMinutes(2)
    private val siteKey = "pk_live_site_key"
    private val origin = "https://example.com"

    private fun tokenAt(instant: Instant) = ConsentOriginToken(secret, ttl, Clock.fixed(instant, ZoneOffset.UTC))

    @Test
    fun `a freshly minted token verifies for its site key and origin`() {
        val signer = tokenAt(now)
        val token = signer.mint(siteKey, origin).token

        assertTrue(signer.isValid(token, siteKey, origin), "a fresh, matching token is valid")
    }

    @Test
    fun `mint reports the configured ttl in seconds`() {
        val minted = tokenAt(now).mint(siteKey, origin)

        assertTrue(minted.expiresInSeconds == ttl.seconds)
    }

    @Test
    fun `a tampered payload is rejected`() {
        val signer = tokenAt(now)
        val token = signer.mint(siteKey, origin).token
        // Flip a character in the payload half; the signature no longer matches.
        val tampered = "X" + token.substring(1)

        assertFalse(signer.isValid(tampered, siteKey, origin), "an edited payload breaks the signature")
    }

    @Test
    fun `a tampered signature is rejected`() {
        val signer = tokenAt(now)
        val token = signer.mint(siteKey, origin).token
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'

        assertFalse(signer.isValid(tampered, siteKey, origin), "an edited signature is rejected")
    }

    @Test
    fun `a token signed with a different secret is rejected`() {
        val token = tokenAt(now).mint(siteKey, origin).token
        val otherSigner = ConsentOriginToken("a-completely-different-secret-0123456789xyz", ttl, Clock.fixed(now, ZoneOffset.UTC))

        assertFalse(otherSigner.isValid(token, siteKey, origin), "only the minting secret can verify a token")
    }

    @Test
    fun `an expired token is rejected`() {
        val token = tokenAt(now).mint(siteKey, origin).token
        // Verify one second past the TTL horizon.
        val laterSigner = tokenAt(now.plus(ttl).plusSeconds(1))

        assertFalse(laterSigner.isValid(token, siteKey, origin), "a token past its expiry is rejected")
    }

    @Test
    fun `a token verifies right up to but not at its expiry instant`() {
        val token = tokenAt(now).mint(siteKey, origin).token

        assertTrue(tokenAt(now.plus(ttl).minusMillis(1)).isValid(token, siteKey, origin), "valid until the last millisecond")
        assertFalse(tokenAt(now.plus(ttl)).isValid(token, siteKey, origin), "expired exactly at the horizon (>=)")
    }

    @Test
    fun `a token for one site key does not verify for another`() {
        val signer = tokenAt(now)
        val token = signer.mint(siteKey, origin).token

        assertFalse(signer.isValid(token, "pk_live_other_site", origin), "the bound site key must match")
    }

    @Test
    fun `an origin-bound token is rejected when the request origin differs or is absent`() {
        val signer = tokenAt(now)
        val token = signer.mint(siteKey, origin).token

        assertFalse(signer.isValid(token, siteKey, "https://evil.example"), "a different origin is rejected")
        assertFalse(signer.isValid(token, siteKey, null), "a missing request origin can't satisfy an origin-bound token")
    }

    @Test
    fun `a token minted without an origin skips the origin check`() {
        val signer = tokenAt(now)
        // No Origin header at mint time (non-browser or same-origin) → origin-unbound token.
        val token = signer.mint(siteKey, null).token

        assertTrue(signer.isValid(token, siteKey, null), "valid with no request origin")
        assertTrue(signer.isValid(token, siteKey, "https://anything.example"), "origin is not enforced when unbound")
    }

    @Test
    fun `structurally malformed tokens are rejected without throwing`() {
        val signer = tokenAt(now)

        assertFalse(signer.isValid("", siteKey, origin), "empty")
        assertFalse(signer.isValid("nodelimiter", siteKey, origin), "no dot separator")
        assertFalse(signer.isValid(".onlysig", siteKey, origin), "empty payload half")
        assertFalse(signer.isValid("onlypayload.", siteKey, origin), "empty signature half")
        assertFalse(signer.isValid("@@@.@@@", siteKey, origin), "non-base64 halves")
    }

    @Test
    fun `a secret shorter than 32 bytes is refused at construction`() {
        assertThrows<IllegalArgumentException> {
            ConsentOriginToken("too-short", ttl, Clock.fixed(now, ZoneOffset.UTC))
        }
    }

    @Test
    fun `a non-positive ttl is refused at construction`() {
        assertThrows<IllegalArgumentException> {
            ConsentOriginToken(secret, Duration.ZERO, Clock.fixed(now, ZoneOffset.UTC))
        }
    }
}
