package com.complyr.consent

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless, HMAC-signed "origin token" for the public consent path. The token proves only that a
 * caller fetched it from `GET /api/v1/consent-token/{siteKey}` recently, from a given browser origin
 * — a real page load, not a blindly replayed `curl` of a captured payload.
 *
 * It is defence-in-depth, NOT a cryptographic origin proof: the mint endpoint is itself
 * unauthenticated and CORS-open (it has to be — so is `/api/v1/consent`), so a scripted attacker can
 * always mint then post. What it buys: a captured consent payload can't be replayed once its token
 * expires (short TTL), a token minted for one origin can't be reused from another (browsers can't
 * forge `Origin`), and issuance can be rate-limited separately. See ADR-13 / ARCHITECTURE §8 for the
 * residual. The consent endpoint treats the token as OPTIONAL — an absent token still records, so an
 * old embedded widget, a privacy browser that strips `Origin`, or a delayed localStorage retry never
 * loses audit evidence; only a *present-but-invalid* token is rejected.
 *
 * Wire format: `base64url(payload) "." base64url(HMAC-SHA256(secret, base64url(payload)))`, where
 * `payload` is the newline-joined `siteKey`, expiry epoch-millis, and origin. Stateless: no storage,
 * no per-page-load DB write. The secret is stable configuration (survives restarts) so a token minted
 * just before a deploy still verifies afterwards and its consent event is not dropped.
 */
class ConsentOriginToken(
    secret: String,
    private val ttl: Duration,
    private val clock: Clock,
) {
    private val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "complyr.consent.origin-token-secret must be at least $MIN_SECRET_BYTES bytes"
        }
        require(!ttl.isZero && !ttl.isNegative) {
            "complyr.consent.origin-token-ttl must be a positive duration (was $ttl)"
        }
    }

    /** A freshly minted token plus how long (seconds) it stays valid — echoed to the widget. */
    data class Minted(
        val token: String,
        val expiresInSeconds: Long,
    )

    /**
     * Mint a token binding [siteKey] and [origin] (the mint request's `Origin` header, or null when
     * absent) to an expiry [ttl] in the future. A null/blank origin mints an origin-unbound token
     * (verification then skips the origin check) so a non-browser or same-origin caller still works.
     */
    fun mint(
        siteKey: String,
        origin: String?,
    ): Minted {
        val expiresAt = clock.instant().plus(ttl).toEpochMilli()
        val payload = encodePayload(siteKey, expiresAt, origin.orEmpty())
        return Minted(token = "$payload.${sign(payload)}", expiresInSeconds = ttl.seconds)
    }

    /**
     * True only if [token] is a well-formed, correctly-signed, unexpired token whose bound site key
     * equals [expectedSiteKey] and — when the token carries an origin — whose origin equals
     * [requestOrigin]. Any structural, signature, expiry, site-key, or origin mismatch returns false;
     * the caller (consent service) then rejects the request. Never throws on malformed input.
     */
    fun isValid(
        token: String,
        expectedSiteKey: String,
        requestOrigin: String?,
    ): Boolean {
        val decoded = decodeVerified(token) ?: return false
        return decoded.siteKey == expectedSiteKey &&
            // >= : the token is dead exactly at its expiry instant, not one tick later.
            clock.instant().toEpochMilli() < decoded.expiresAt &&
            // Origin binding is only enforced when the token carries one (browser-minted). An
            // origin-unbound token (blank origin) skips this; sig + expiry + site key still gate it.
            (decoded.origin.isEmpty() || decoded.origin == requestOrigin)
    }

    /**
     * Verify the signature and decode the payload into its three fields, or null if [token] is
     * structurally malformed, wrongly signed, or unparseable. A fail-closed parser: every branch is a
     * guard that returns null, so the public [isValid] stays a flat boolean over the decoded values.
     */
    @Suppress("ReturnCount") // sequential fail-closed guards read clearer than nesting; each is a reject
    private fun decodeVerified(token: String): DecodedToken? {
        val separator = token.indexOf('.')
        if (separator <= 0 || separator == token.length - 1) return null
        val payload = token.substring(0, separator)
        val providedSig = token.substring(separator + 1)
        if (!signatureMatches(payload, providedSig)) return null

        val decoded = runCatching { String(decoder.decode(payload), Charsets.UTF_8) }.getOrNull() ?: return null
        val fields = decoded.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val expiresAt = fields[EXPIRY_FIELD].toLongOrNull() ?: return null
        return DecodedToken(siteKey = fields[SITE_KEY_FIELD], expiresAt = expiresAt, origin = fields[ORIGIN_FIELD])
    }

    private fun encodePayload(
        siteKey: String,
        expiresAt: Long,
        origin: String,
    ): String {
        val raw = listOf(siteKey, expiresAt.toString(), origin).joinToString(FIELD_SEPARATOR)
        return encoder.encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    /** Raw HMAC-SHA256 bytes over [payload]. A fresh [Mac] per call keeps this thread-safe for the shared bean. */
    private fun signRaw(payload: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(secretKey)
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    private fun sign(payload: String): String = encoder.encodeToString(signRaw(payload))

    /** Constant-time signature check over the raw HMAC bytes; a malformed base64 signature fails closed. */
    private fun signatureMatches(
        payload: String,
        providedSig: String,
    ): Boolean {
        val provided = runCatching { decoder.decode(providedSig) }.getOrNull() ?: return false
        return MessageDigest.isEqual(signRaw(payload), provided)
    }

    /** A verified token's payload, decoded into its three fields. */
    private data class DecodedToken(
        val siteKey: String,
        val expiresAt: Long,
        val origin: String,
    )

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val MIN_SECRET_BYTES = 32

        // Newline can't appear in a site key, an epoch-millis number, or an HTTP Origin header value,
        // so it unambiguously separates the three payload fields.
        private const val FIELD_SEPARATOR = "\n"
        private const val FIELD_COUNT = 3
        private const val SITE_KEY_FIELD = 0
        private const val EXPIRY_FIELD = 1
        private const val ORIGIN_FIELD = 2
    }
}
