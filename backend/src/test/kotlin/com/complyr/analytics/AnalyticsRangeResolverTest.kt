package com.complyr.analytics

import com.complyr.analytics.dto.AnalyticsFilter
import com.complyr.analytics.dto.AnalyticsRange
import com.complyr.billing.EntitlementService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [AnalyticsRangeResolver] — the one place a consent-evidence read window is decided, and
 * therefore the one place ADR-16's read-layer retention floor is applied. If these pass, no caller can read
 * past what the customer's plan sells them, whatever `from` they send.
 */
class AnalyticsRangeResolverTest {
    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-08-12T12:00:00Z")
    private val entitlementService = mockk<EntitlementService>()
    private val resolver = AnalyticsRangeResolver(entitlementService, Clock.fixed(now, ZoneOffset.UTC))

    private fun floorAt(floor: Instant) {
        every { entitlementService.consentRetentionFloor(userId) } returns floor
    }

    @Test
    fun `an empty filter resolves to the trailing default window ending now`() {
        floorAt(now.minus(AnalyticsRangeResolver.DEFAULT_WINDOW).minusSeconds(1))

        val range = resolver.resolve(userId, AnalyticsFilter())

        assertEquals(now, range.to)
        assertEquals(now.minus(AnalyticsRangeResolver.DEFAULT_WINDOW), range.from)
    }

    @Test
    fun `a request reaching past the plan retention is clamped to the floor, not rejected`() {
        val floor = now.minusSeconds(365 * 86_400)
        floorAt(floor)

        val range = resolver.resolve(userId, AnalyticsFilter(from = now.minusSeconds(3L * 365 * 86_400)))

        // Silently narrowed: asking for older evidence yields fewer rows, never an error.
        assertEquals(floor, range.from)
    }

    @Test
    fun `a request inside the plan retention is honoured verbatim`() {
        floorAt(now.minusSeconds(365 * 86_400))
        val from = now.minusSeconds(7 * 86_400)
        val to = now.minusSeconds(86_400)

        val range = resolver.resolve(userId, AnalyticsFilter(from = from, to = to))

        assertEquals(from, range.from)
        assertEquals(to, range.to)
    }

    @Test
    fun `an explicit to shifts the default window back with it`() {
        floorAt(now.minusSeconds(365 * 86_400))
        val to = now.minusSeconds(10 * 86_400)

        val range = resolver.resolve(userId, AnalyticsFilter(to = to))

        assertEquals(to, range.to)
        assertEquals(to.minus(AnalyticsRangeResolver.DEFAULT_WINDOW), range.from)
    }

    @Test
    fun `prior window is the current window shifted back by its own length`() {
        val current = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)

        val prior = requireNotNull(resolver.priorWindow(current, now.minusSeconds(365 * 86_400)))

        // Contiguous and equal-length: [now-60d, now-30d) immediately before [now-30d, now).
        assertEquals(current.from, prior.to)
        assertEquals(now.minusSeconds(60 * 86_400), prior.from)
    }

    @Test
    fun `prior window is omitted when it would read below the plan retention floor`() {
        // Floor sits inside the prior window, so a full-length baseline is unreadable — omit rather than skew.
        val current = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)

        assertNull(resolver.priorWindow(current, now.minusSeconds(45 * 86_400)))
    }

    @Test
    fun `prior window is kept when it sits exactly at the retention floor`() {
        val current = AnalyticsRange(from = now.minusSeconds(30 * 86_400), to = now)

        val prior = requireNotNull(resolver.priorWindow(current, now.minusSeconds(60 * 86_400)))
        assertEquals(now.minusSeconds(60 * 86_400), prior.from)
    }
}
