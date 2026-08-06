package com.complyr.scan.dto

/**
 * A single compliance finding from a completed scan. [code] is a stable machine token the dashboard
 * localizes (i18n from day one — the backend emits no user-facing prose); [count] is the affected-cookie
 * count the dashboard interpolates into that copy. [severity] is one of `critical` | `warning` | `info`.
 */
data class ComplianceIssue(
    val code: String,
    val severity: String,
    val count: Int,
)

/**
 * The compliance summary attached to a completed [ScanDetailResponse]: an indicative 0–100 [score]
 * (higher is better) and the [issues] that drove it, most-severe first.
 *
 * The score prioritizes fixes — it is **not** a legal determination, and the dashboard frames it that
 * way. It is only ever populated for a `done` scan; a queued/running/failed scan carries `null`.
 */
data class ComplianceReport(
    val score: Int,
    val issues: List<ComplianceIssue>,
)
