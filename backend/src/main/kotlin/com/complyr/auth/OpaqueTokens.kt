package com.complyr.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque token helpers shared by refresh and verification/reset tokens:
 * 32 bytes of SecureRandom, base64url on the wire, SHA-256 hex at rest.
 */
object OpaqueTokens {
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
