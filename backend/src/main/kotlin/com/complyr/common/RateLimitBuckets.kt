package com.complyr.common

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A bounded, in-memory registry of per-key token buckets (Bucket4j) with idle-first eviction.
 *
 * Shared by the IP-keyed [RateLimitFilter] (public/unauthenticated tiers) and the user-keyed
 * [AuthenticatedRateLimitFilter] (authenticated tiers). Each filter owns its own registry so the
 * two key spaces — and their caps — stay independent. Keys are opaque and caller-namespaced by
 * tier; they are used only as in-memory map keys, never logged or persisted (CLAUDE.md #4).
 *
 * A given key must always be consumed with the same [capacity]; callers guarantee this by folding
 * the tier (which fixes the capacity) into the key.
 */
class RateLimitBuckets(
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    // Time source for every bucket's refill. Production uses the system clock; tests inject a
    // controllable meter to advance virtual time (so a bucket can deterministically refill to full
    // and register as "idle") without sleeping. Not a configured property — purely a test seam.
    private val timeMeter: TimeMeter = TimeMeter.SYSTEM_MILLISECONDS,
) {
    private val buckets = ConcurrentHashMap<String, TrackedBucket>()

    /** Number of keys currently tracked. Exposed for tests/metrics; approximate under concurrency. */
    val trackedKeyCount: Int get() = buckets.size

    /**
     * Spend one token for [key], lazily creating a full [capacity]-per-minute bucket on first use.
     * Returns true when a token was available (allow), false when the bucket is drained (throttle).
     *
     * Eviction runs only on the miss path (a first-seen key, the only thing that grows the map), not
     * on every call: a repeat request for an existing key — the hot path for a stable user/IP set —
     * skips the O(n) idle scan entirely, so the sweep stays proportional to new-key churn rather than
     * total traffic. The `get` is a lock-free read; the `computeIfAbsent` still creates the bucket
     * atomically, so concurrent first-hits on the same key share one bucket.
     */
    fun tryConsume(
        key: String,
        capacity: Long,
    ): Boolean {
        val tracked =
            buckets[key] ?: run {
                evictIdleWhenOverCap()
                buckets.computeIfAbsent(key) { TrackedBucket(newBucket(capacity), capacity) }
            }
        return tracked.bucket.tryConsume(1)
    }

    /**
     * Memory bound: when tracking too many keys, evict only idle buckets — those refilled back to
     * their own full capacity, i.e. keys not currently being throttled. Never drop a bucket
     * mid-throttle, so an attacker spraying many distinct keys cannot push the map over the cap to
     * reset a targeted key's (or their own) limit. An evicted idle bucket is recreated — starting
     * full — on that key's next request, which is harmless.
     */
    private fun evictIdleWhenOverCap() {
        if (buckets.size <= maxTrackedKeys) return
        // First choice: drop only idle buckets (refilled to full) — never resets a key mid-throttle,
        // so a key-spray can't push the map over cap to reset a target.
        buckets.values.removeIf { it.bucket.availableTokens >= it.capacity }
        // Absolute bound: a distributed spray can keep every bucket partially drained so none are
        // idle. Still cap memory by dropping arbitrary entries. At >cap distinct keys/min this is an
        // attack the edge (Cloudflare) is the primary control for, and a reset bucket only grants one
        // key a fresh (already generous) allowance — benign.
        if (buckets.size > maxTrackedKeys) {
            val iterator = buckets.keys.iterator()
            while (buckets.size > maxTrackedKeys && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun newBucket(perMinute: Long): Bucket =
        Bucket
            .builder()
            .withCustomTimePrecision(timeMeter)
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(perMinute)
                    .refillGreedy(perMinute, Duration.ofMinutes(1))
                    .build(),
            ).build()

    private class TrackedBucket(
        val bucket: Bucket,
        val capacity: Long,
    )

    companion object {
        const val DEFAULT_MAX_TRACKED_KEYS = 10_000
    }
}
