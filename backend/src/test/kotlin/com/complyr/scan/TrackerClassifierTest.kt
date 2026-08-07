package com.complyr.scan

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

/**
 * The marketing-tracker counter over the *real* bundled dataset (`resources/trackers/trackers.json`,
 * on the test classpath). It exercises the whole load path — a malformed/missing resource would fail
 * construction here — and the count semantics the compliance finding depends on: distinct-by-signature
 * (so CDN host-sharding never inflates the number) and marketing-only (analytics/necessary are ignored).
 */
class TrackerClassifierTest {
    private val classifier = TrackerClassifier(JsonMapper.builder().build())

    @Test
    fun `counts distinct marketing trackers across observed hosts`() {
        // doubleclick (via subdomain) + facebook connect = two distinct marketing vendors.
        val count = classifier.countMarketingTrackers(setOf("ad.doubleclick.net", "connect.facebook.net"))

        assertEquals(2, count)
    }

    @Test
    fun `host-sharding across one vendor counts once`() {
        // a/b/c.doubleclick.net all normalize to the same signature — one marketing vendor, not three.
        val count =
            classifier.countMarketingTrackers(
                setOf("a.doubleclick.net", "b.doubleclick.net", "c.doubleclick.net"),
            )

        assertEquals(1, count)
    }

    @Test
    fun `analytics and unknown hosts are ignored`() {
        // google-analytics is analytics (not marketing); example.com is unknown — neither counts.
        val count = classifier.countMarketingTrackers(setOf("google-analytics.com", "example.com"))

        assertEquals(0, count)
    }

    @Test
    fun `no observed hosts is zero`() {
        assertEquals(0, classifier.countMarketingTrackers(emptySet()))
    }
}
