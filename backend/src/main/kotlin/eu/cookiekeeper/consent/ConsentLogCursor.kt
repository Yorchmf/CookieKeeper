package eu.cookiekeeper.consent

import eu.cookiekeeper.common.ApiException
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Client-supplied consent-log page cursor was not a value we minted — 400. Returned instead of silently
 * restarting from the newest page, which would make an infinite-scroll client loop forever on the first page.
 */
class InvalidCursorException : ApiException(HttpStatus.BAD_REQUEST, code = "INVALID_CURSOR", message = "Malformed page cursor")

/**
 * Keyset anchor for the consent log: the `(createdAt, eventId)` of a page's last row. Paging orders by
 * `createdAt DESC, eventId DESC` so it rides the existing `(site_id, created_at)` index and stays inside the
 * relevant monthly partitions (ordering by `eventId` alone would forfeit that partition pruning). `eventId` is
 * the UUIDv7 tiebreaker for rows sharing an exact timestamp.
 */
data class ConsentLogCursorPosition(
    val createdAt: Instant,
    val eventId: UUID,
)

/**
 * Opaque, base64url-wrapped encoding of a [ConsentLogCursorPosition] so a page token reads as a token rather
 * than internal ids, and a client can't hand us an arbitrary timestamp/UUID as an out-of-band filter. Decoding
 * is total: anything that isn't our own encoding of a `createdAt|eventId` pair raises [InvalidCursorException].
 */
object ConsentLogCursor {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val FIELD_COUNT = 2
    private const val SEPARATOR = "|"

    fun encode(position: ConsentLogCursorPosition): String =
        encoder.encodeToString("${position.createdAt}$SEPARATOR${position.eventId}".toByteArray(Charsets.UTF_8))

    fun decode(cursor: String): ConsentLogCursorPosition =
        try {
            val parts = String(decoder.decode(cursor), Charsets.UTF_8).split(SEPARATOR)
            require(parts.size == FIELD_COUNT) { "cursor must hold exactly $FIELD_COUNT fields" }
            ConsentLogCursorPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]))
        } catch (_: IllegalArgumentException) {
            throw InvalidCursorException()
        } catch (_: DateTimeParseException) {
            throw InvalidCursorException()
        }
}
