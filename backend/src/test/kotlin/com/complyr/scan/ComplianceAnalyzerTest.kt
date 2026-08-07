package com.complyr.scan

import com.complyr.banner.ConsentCategory
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure scoring/issue logic over a completed scan's classified cookies. Because our crawl is a
 * *before-consent* crawl, any recorded non-necessary cookie is a pre-consent finding — the tests pin the
 * severity buckets, the per-issue score deductions, the retention (long-lived) threshold, and the
 * most-severe-first ordering. No English wording is asserted: the report emits stable machine codes only.
 */
class ComplianceAnalyzerTest {
    private val now: Instant = Instant.parse("2026-08-01T12:00:00Z")

    // Defaults model a properly-secured cookie (Secure + HttpOnly) so a test only trips the insecure
    // finding when it explicitly opts out — existing severity assertions stay isolated from that check.
    private fun cookie(
        name: String,
        category: String?,
        isKnown: Boolean = category != null,
        expiry: String = ScanCookieMapper.SESSION_EXPIRY,
        secure: Boolean = true,
        httpOnly: Boolean = true,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = UUID.randomUUID(),
            name = name,
            domain = "example.com",
            expiry = expiry,
            category = category,
            provider = null,
            isKnown = isKnown,
            secure = secure,
            httpOnly = httpOnly,
        )

    @Test
    fun `no cookies scores a clean 100 with no issues`() {
        val report = ComplianceAnalyzer.analyze(emptyList(), now)

        assertEquals(100, report.score)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `only necessary cookies stay a clean 100`() {
        val report = ComplianceAnalyzer.analyze(listOf(cookie("sid", ConsentCategory.NECESSARY.key)), now)

        assertEquals(100, report.score)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `a non-marketing non-necessary cookie is pre-consent tracking, critical, minus 30`() {
        val report = ComplianceAnalyzer.analyze(listOf(cookie("_ga", ConsentCategory.STATISTICS.key)), now)

        assertEquals(70, report.score)
        assertEquals(1, report.issues.size)
        val issue = report.issues.single()
        assertEquals("pre_consent_tracking", issue.code)
        assertEquals("critical", issue.severity)
        assertEquals(1, issue.count)
    }

    @Test
    fun `preferences cookies also count as pre-consent tracking`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(
                    cookie("lang", ConsentCategory.PREFERENCES.key),
                    cookie("theme", ConsentCategory.PREFERENCES.key),
                ),
                now,
            )

        val issue = report.issues.single { it.code == "pre_consent_tracking" }
        assertEquals(2, issue.count)
        assertEquals(70, report.score)
    }

    @Test
    fun `marketing cookies are their own critical bucket, disjoint from pre-consent tracking`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(
                    cookie("_ga", ConsentCategory.STATISTICS.key),
                    cookie("_fbp", ConsentCategory.MARKETING.key),
                ),
                now,
            )

        val preConsent = report.issues.single { it.code == "pre_consent_tracking" }
        val marketing = report.issues.single { it.code == "marketing_cookies" }
        // Each cookie is counted once, in exactly one bucket — no double count.
        assertEquals(1, preConsent.count)
        assertEquals(1, marketing.count)
        // Two criticals: 100 - 30 - 30.
        assertEquals(40, report.score)
    }

    @Test
    fun `a persistent expiry beyond a year adds a long-lived warning`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, expiry = "2028-01-01T00:00:00Z")),
                now,
            )

        // Same cookie drives both the pre-consent critical and the retention warning: 100 - 30 - 10.
        assertEquals(60, report.score)
        val longLived = report.issues.single { it.code == "long_lived_cookies" }
        assertEquals("warning", longLived.severity)
        assertEquals(1, longLived.count)
    }

    @Test
    fun `a persistent expiry under a year is not long-lived`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, expiry = "2026-10-01T00:00:00Z")),
                now,
            )

        assertTrue(report.issues.none { it.code == "long_lived_cookies" })
    }

    @Test
    fun `a session cookie is never long-lived`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, expiry = ScanCookieMapper.SESSION_EXPIRY)),
                now,
            )

        assertTrue(report.issues.none { it.code == "long_lived_cookies" })
    }

    @Test
    fun `an unparseable expiry does not throw and is not long-lived`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, expiry = "not-a-date")),
                now,
            )

        assertTrue(report.issues.none { it.code == "long_lived_cookies" })
    }

    @Test
    fun `an unrecognized cookie is an info-level needs-review finding`() {
        val report = ComplianceAnalyzer.analyze(listOf(cookie("mystery", category = null, isKnown = false)), now)

        assertEquals(97, report.score)
        val issue = report.issues.single()
        assertEquals("unclassified_cookies", issue.code)
        assertEquals("info", issue.severity)
        assertEquals(1, issue.count)
    }

    @Test
    fun `a known-but-uncategorized cookie is treated as unclassified, not scored as a category`() {
        // Defensive: the classifier invariant is isKnown ⇒ category set, but a null category must never
        // slip into a severity bucket — it falls through to needs-review, mirroring ScanDetailResponse.
        val report = ComplianceAnalyzer.analyze(listOf(cookie("weird", category = null, isKnown = true)), now)

        assertEquals(listOf("unclassified_cookies"), report.issues.map { it.code })
    }

    @Test
    fun `a non-essential cookie without Secure or HttpOnly adds an insecure warning`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, secure = false, httpOnly = false)),
                now,
            )

        // Same cookie drives both the pre-consent critical and the insecure warning: 100 - 30 - 10.
        assertEquals(60, report.score)
        val insecure = report.issues.single { it.code == "insecure_cookies" }
        assertEquals("warning", insecure.severity)
        assertEquals(1, insecure.count)
    }

    @Test
    fun `a cookie carrying Secure alone is not flagged insecure`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, secure = true, httpOnly = false)),
                now,
            )

        assertTrue(report.issues.none { it.code == "insecure_cookies" })
    }

    @Test
    fun `a cookie carrying HttpOnly alone is not flagged insecure`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("_ga", ConsentCategory.STATISTICS.key, secure = false, httpOnly = true)),
                now,
            )

        assertTrue(report.issues.none { it.code == "insecure_cookies" })
    }

    @Test
    fun `a necessary cookie without transport flags is not flagged insecure`() {
        // Necessary cookies are outside the insecure check — only classified non-necessary cookies count.
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("sid", ConsentCategory.NECESSARY.key, secure = false, httpOnly = false)),
                now,
            )

        assertEquals(100, report.score)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `an unclassified cookie without transport flags is not flagged insecure`() {
        // Unrecognized cookies are their own info finding and never enter the non-necessary insecure set.
        val report =
            ComplianceAnalyzer.analyze(
                listOf(cookie("mystery", category = null, isKnown = false, secure = false, httpOnly = false)),
                now,
            )

        assertEquals(listOf("unclassified_cookies"), report.issues.map { it.code })
    }

    @Test
    fun `third-party marketing trackers add a warning carrying the observed count`() {
        // No cookies at all — the finding is driven purely by the count the crawl threaded in, proving
        // it is independent of the cookie-based checks (a tracker can ride a request without a cookie).
        val report = ComplianceAnalyzer.analyze(emptyList(), now, marketingTrackerCount = 3)

        assertEquals(90, report.score, "one warning off a clean 100")
        val trackers = report.issues.single()
        assertEquals("third_party_trackers", trackers.code)
        assertEquals("warning", trackers.severity)
        assertEquals(3, trackers.count, "the count is surfaced verbatim, not re-derived")
    }

    @Test
    fun `zero third-party trackers emit no tracker finding`() {
        val report = ComplianceAnalyzer.analyze(emptyList(), now, marketingTrackerCount = 0)

        assertEquals(100, report.score)
        assertTrue(report.issues.none { it.code == "third_party_trackers" })
    }

    @Test
    fun `issues are ordered most-severe first`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(
                    cookie("mystery", category = null, isKnown = false),
                    cookie(
                        "_ga",
                        ConsentCategory.STATISTICS.key,
                        expiry = "2028-01-01T00:00:00Z",
                        secure = false,
                        httpOnly = false,
                    ),
                    cookie("_fbp", ConsentCategory.MARKETING.key),
                ),
                now,
                marketingTrackerCount = 2,
            )

        // The three warnings sort together after the two criticals and before the unclassified info
        // finding: long-lived, then insecure, then third-party trackers.
        assertEquals(
            listOf(
                "pre_consent_tracking",
                "marketing_cookies",
                "long_lived_cookies",
                "insecure_cookies",
                "third_party_trackers",
                "unclassified_cookies",
            ),
            report.issues.map { it.code },
        )
        // 100 - 30 - 30 - 10 - 10 - 10 - 3.
        assertEquals(7, report.score)
    }
}
