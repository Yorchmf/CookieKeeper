package com.complyr.scan

import com.complyr.banner.ConsentCategory
import com.complyr.scan.dto.ComplianceIssue
import com.complyr.scan.dto.ComplianceReport
import java.time.Duration
import java.time.Instant

/**
 * Derives a plain-language compliance report from a completed scan's classified cookies.
 *
 * Our crawl is a *before-consent* crawl (see [ScanCookieEntity]), so every non-necessary cookie it
 * records was set with no prior consent — the core ePrivacy/GDPR violation. This turns that (plus cookie
 * retention and unclassified findings) into an indicative 0–100 score and a severity-ranked issue list.
 *
 * The score is an **indicative** signal to prioritize fixes, NOT a legal determination — the dashboard
 * frames it that way. Issues carry stable machine [ComplianceIssue.code]s and a [ComplianceIssue.count];
 * all user-facing wording is localized in the dashboard (i18n from day one — no English strings here).
 *
 * Deliberately **not** emitted, to avoid a fabricated score, because the current scan schema persists no
 * backing data for them: missing-consent-banner, insecure cookie flags (Secure/HttpOnly), and
 * third-party request trackers. Adding those requires the scan engine to persist the signals first.
 *
 * Pure and stateless: the caller supplies `now` (from the injected `Clock`) so the retention check is
 * deterministic under test.
 */
object ComplianceAnalyzer {
    private const val PERFECT_SCORE = 100
    private const val WORST_SCORE = 0
    private const val DAYS_PER_YEAR = 365L

    private const val CRITICAL_PENALTY = 30
    private const val WARNING_PENALTY = 10
    private const val INFO_PENALTY = 3

    /** Retention threshold: a non-essential cookie living longer than this is flagged (GDPR data minimization). */
    private val LONG_LIVED_THRESHOLD = Duration.ofDays(DAYS_PER_YEAR)

    /** Per-issue score deductions, keyed by wire severity. One deduction per issue, not per cookie. */
    private val PENALTIES =
        mapOf(
            "critical" to CRITICAL_PENALTY,
            "warning" to WARNING_PENALTY,
            "info" to INFO_PENALTY,
        )

    fun analyze(
        cookies: List<ScanCookieEntity>,
        now: Instant,
    ): ComplianceReport {
        val (classified, unclassified) = cookies.partition { it.isKnown && it.category != null }
        val nonNecessary = classified.filter { it.category != ConsentCategory.NECESSARY.key }

        val marketing = nonNecessary.filter { it.category == ConsentCategory.MARKETING.key }
        val otherPreConsent = nonNecessary.filter { it.category != ConsentCategory.MARKETING.key }
        val longLived = nonNecessary.filter { isLongLived(it.expiry, now) }

        // Ordered most-severe first: two criticals, then the warning, then the info.
        val issues =
            buildList {
                if (otherPreConsent.isNotEmpty()) {
                    add(ComplianceIssue("pre_consent_tracking", "critical", otherPreConsent.size))
                }
                if (marketing.isNotEmpty()) {
                    add(ComplianceIssue("marketing_cookies", "critical", marketing.size))
                }
                if (longLived.isNotEmpty()) {
                    add(ComplianceIssue("long_lived_cookies", "warning", longLived.size))
                }
                if (unclassified.isNotEmpty()) {
                    add(ComplianceIssue("unclassified_cookies", "info", unclassified.size))
                }
            }

        val score =
            issues
                .fold(PERFECT_SCORE) { acc, issue -> acc - PENALTIES.getValue(issue.severity) }
                .coerceIn(WORST_SCORE, PERFECT_SCORE)
        return ComplianceReport(score = score, issues = issues)
    }

    /**
     * True when [expiry] is a persistent expiry more than a year out. [expiry] is either
     * [ScanCookieMapper.SESSION_EXPIRY] or an ISO-8601 instant (see [ScanCookieMapper.formatExpiry]); an
     * unparseable value is treated as not-long-lived rather than throwing (fail-open on a display signal).
     */
    private fun isLongLived(
        expiry: String?,
        now: Instant,
    ): Boolean {
        if (expiry == null || expiry == ScanCookieMapper.SESSION_EXPIRY) return false
        val expiresAt = runCatching { Instant.parse(expiry) }.getOrNull() ?: return false
        return expiresAt.isAfter(now.plus(LONG_LIVED_THRESHOLD))
    }
}
