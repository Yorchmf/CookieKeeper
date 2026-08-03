package com.complyr.consent

import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The opaque page cursor must round-trip its `(createdAt, eventId)` keyset position exactly and reject anything
 * it did not mint, so a tampered or truncated cursor becomes a clean 400 rather than a silently-ignored filter.
 */
class ConsentLogCursorTest {
    @Test
    fun `encode then decode returns the original keyset position`() {
        val position = ConsentLogCursorPosition(Instant.parse("2026-08-01T10:00:00Z"), UUID.randomUUID())

        val decoded = ConsentLogCursor.decode(ConsentLogCursor.encode(position))

        assertEquals(position, decoded)
    }

    @Test
    fun `decode rejects a value that is not our base64url-wrapped uuid`() {
        assertThrows<InvalidCursorException> { ConsentLogCursor.decode("not-a-cursor!!") }
    }

    @Test
    fun `decode rejects base64 that does not wrap a uuid`() {
        val notAUuid =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("hello".toByteArray())

        assertThrows<InvalidCursorException> { ConsentLogCursor.decode(notAUuid) }
    }
}
