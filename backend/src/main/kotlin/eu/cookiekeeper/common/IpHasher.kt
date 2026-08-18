package eu.cookiekeeper.common

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-way, rotating-salt hashing of visitor IP addresses (CLAUDE.md constraint #4:
 * no raw IPs at rest). Produces `HMAC-SHA256(dailySalt, ip)` as lowercase hex.
 *
 * The salt is a random 256-bit value held only in memory and regenerated every UTC day
 * (and on process restart). Once a day rolls over the previous salt is gone, so past
 * `ip_hash` values can never be reversed or correlated to a new day's hashes — the small
 * IPv4 space cannot be brute-forced without the salt. Within a day the hash is stable, so
 * events from one visitor stay coarsely linkable for audit/abuse analysis; the durable
 * per-visitor link is the cookie-stored `visitor_id`, not this hash.
 *
 * v1 runs a single backend instance (docs/ARCHITECTURE.md), so a per-instance in-memory
 * salt is sufficient. If we ever scale horizontally, the salt must move to a shared,
 * daily-rotated secret — otherwise instances would hash the same IP differently.
 */
@Component
class IpHasher(
    private val clock: Clock,
) {
    private val random = SecureRandom()
    private val lock = Any()

    @Volatile private var saltEpochDay: Long = Long.MIN_VALUE

    @Volatile private var salt: ByteArray = ByteArray(0)

    /** Returns the rotating-salt HMAC of [ip] as lowercase hex, or null when [ip] is blank. */
    fun hash(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        val key = currentSalt()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(ip.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun currentSalt(): ByteArray {
        val today =
            clock
                .instant()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
        if (today != saltEpochDay) {
            synchronized(lock) {
                if (today != saltEpochDay) {
                    salt = ByteArray(SALT_BYTES).also(random::nextBytes)
                    saltEpochDay = today
                }
            }
        }
        return salt
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val SALT_BYTES = 32
    }
}
