package com.complyr.common

import io.github.bucket4j.TimeMeter
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the bounded-eviction core of [RateLimitBuckets] — the reason it was extracted, and the
 * carrier of a security invariant (a key-spray must not reset an actively-throttled victim while
 * idle buckets remain to reclaim). Idle detection is refill-dependent, so a controllable
 * [TimeMeter] advances virtual time to make a bucket deterministically refill to full (idle)
 * without sleeping.
 */
class RateLimitBucketsTest {
    // A TimeMeter whose clock only moves when the test advances it — lets a bucket refill on demand.
    private class MutableTimeMeter(
        private var nanos: Long = 0,
    ) : TimeMeter {
        fun advance(duration: Duration) {
            nanos += duration.toNanos()
        }

        override fun currentTimeNanos(): Long = nanos

        override fun isWallClockBased(): Boolean = false
    }

    @Test
    fun `under the cap no bucket is evicted — each key keeps its own drained state`() {
        val buckets = RateLimitBuckets(maxTrackedKeys = 100)

        // Drain key A to empty (capacity 2).
        assertTrue(buckets.tryConsume("A", 2))
        assertTrue(buckets.tryConsume("A", 2))
        assertFalse(buckets.tryConsume("A", 2))

        // Adding other keys (still well under cap) must not reset A's bucket.
        repeat(10) { assertTrue(buckets.tryConsume("other-$it", 2)) }

        assertFalse(buckets.tryConsume("A", 2), "A must stay throttled — no eviction under cap")
        assertEquals(11, buckets.trackedKeyCount)
    }

    @Test
    fun `over the cap with only non-idle buckets the map size stays bounded`() {
        val cap = 3
        val buckets = RateLimitBuckets(maxTrackedKeys = cap)

        // Every key is freshly consumed (capacity 5, one token spent) → none is idle, so the
        // idle-first pass frees nothing and the absolute-bound fallback must cap growth.
        repeat(50) { buckets.tryConsume("k$it", 5) }

        // Steady state is cap+1: each new key evicts back to cap, then inserts one.
        assertTrue(buckets.trackedKeyCount <= cap + 1, "size ${buckets.trackedKeyCount} must be bounded near cap")
    }

    @Test
    fun `idle-first eviction reclaims idle buckets before an actively-throttled key`() {
        val clock = MutableTimeMeter()
        val cap = 2
        val buckets = RateLimitBuckets(maxTrackedKeys = cap, timeMeter = clock)

        // Two keys that will become idle: consume once (capacity 5), then let time refill them full.
        buckets.tryConsume("idle-1", 5)
        buckets.tryConsume("idle-2", 5)
        clock.advance(Duration.ofMinutes(2)) // > refill window → idle-1 / idle-2 back to full (idle)

        // Victim key: drained to empty and thus NON-idle. Insert stays at cap (2 idle + this = 3).
        assertTrue(buckets.tryConsume("victim", 1))
        assertFalse(buckets.tryConsume("victim", 1), "victim is drained")

        // A new key pushes over cap and triggers eviction: the two idle keys are reclaimed first,
        // the drained victim is preserved.
        buckets.tryConsume("trigger", 1)

        assertFalse(
            buckets.tryConsume("victim", 1),
            "an actively-throttled key must survive a key-spray while idle buckets exist to reclaim",
        )
        // idle-1 + idle-2 evicted; victim + trigger remain.
        assertEquals(2, buckets.trackedKeyCount)
    }

    @Test
    fun `an evicted idle key is recreated with a fresh full allowance on its next request`() {
        val clock = MutableTimeMeter()
        val buckets = RateLimitBuckets(maxTrackedKeys = 1, timeMeter = clock)

        // Fill one slot, let it go idle, then force it out with a second key.
        buckets.tryConsume("evicted", 3)
        clock.advance(Duration.ofMinutes(2))
        buckets.tryConsume("keeps-slot", 3)

        // "evicted" was dropped; its next request builds a fresh bucket starting at full capacity.
        assertTrue(buckets.tryConsume("evicted", 3))
        assertTrue(buckets.tryConsume("evicted", 3))
        assertTrue(buckets.tryConsume("evicted", 3))
    }
}
