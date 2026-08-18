package eu.cookiekeeper.common

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpHasherTest {
    /** Clock whose instant can be advanced between calls to exercise daily salt rotation. */
    private class MutableClock(
        var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private val day0 = Instant.parse("2026-07-30T12:00:00Z")

    @Test
    fun `blank or null input hashes to null`() {
        val hasher = IpHasher(Clock.fixed(day0, ZoneOffset.UTC))
        assertNull(hasher.hash(null))
        assertNull(hasher.hash(""))
        assertNull(hasher.hash("   "))
    }

    @Test
    fun `same IP within a day is stable and rendered as 64-char lowercase hex`() {
        val hasher = IpHasher(Clock.fixed(day0, ZoneOffset.UTC))
        val first = requireNotNull(hasher.hash("203.0.113.7"))
        val second = requireNotNull(hasher.hash("203.0.113.7"))

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" }, first)
    }

    @Test
    fun `different IPs hash differently and never echo the raw IP`() {
        val hasher = IpHasher(Clock.fixed(day0, ZoneOffset.UTC))
        val a = requireNotNull(hasher.hash("203.0.113.7"))
        val b = requireNotNull(hasher.hash("203.0.113.8"))

        assertNotEquals(a, b)
        assertTrue(!a.contains("203.0.113.7"))
    }

    @Test
    fun `salt rotates across a UTC day boundary so the same IP no longer correlates`() {
        val clock = MutableClock(day0)
        val hasher = IpHasher(clock)
        val beforeRotation = requireNotNull(hasher.hash("203.0.113.7"))

        clock.instant = day0.plus(Duration.ofDays(1))
        val afterRotation = requireNotNull(hasher.hash("203.0.113.7"))

        assertNotEquals(beforeRotation, afterRotation)
    }
}
