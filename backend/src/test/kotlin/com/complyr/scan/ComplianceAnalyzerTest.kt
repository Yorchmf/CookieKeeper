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

    private fun cookie(
        name: String,
        category: String?,
        isKnown: Boolean = category != null,
        expiry: String = ScanCookieMapper.SESSION_EXPIRY,
    ): ScanCookieEntity =
        ScanCookieEntity(
            scanId = UUID.randomUUID(),
            name = name,
            domain = "example.com",
            expiry = expiry,
            category = category,
            provider = null,
            isKnown = isKnown,
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
    fun `issues are ordered most-severe first`() {
        val report =
            ComplianceAnalyzer.analyze(
                listOf(
                    cookie("mystery", category = null, isKnown = false),
                    cookie("_ga", ConsentCategory.STATISTICS.key, expiry = "2028-01-01T00:00:00Z"),
                    cookie("_fbp", ConsentCategory.MARKETING.key),
                ),
                now,
            )

        assertEquals(
            listOf("pre_consent_tracking", "marketing_cookies", "long_lived_cookies", "unclassified_cookies"),
            report.issues.map { it.code },
        )
        // 100 - 30 - 30 - 10 - 3.
        assertEquals(27, report.score)
    }
}
