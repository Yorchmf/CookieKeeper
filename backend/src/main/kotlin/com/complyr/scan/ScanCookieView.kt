package com.complyr.scan

/**
 * The read-only projection of an observed cookie that [ComplianceAnalyzer] scores, implemented by both
 * [ScanCookieEntity] (authenticated per-site scan) and [PublicScanCookieEntity] (anonymous free-scan
 * funnel). One analyzer therefore scores both paths without depending on which table a row came from or
 * on any persistence detail (id, FK). Only the compliance-relevant fields live here.
 *
 * [secure]/[httpOnly] are the cookie's transport flags (Playwright `Cookie.secure` / `Cookie.httpOnly`);
 * a non-essential cookie missing both is served in the clear and readable from script — an insecure-flag
 * finding (see [ComplianceAnalyzer]).
 */
interface ScanCookieView {
    val category: String?
    val expiry: String?
    val isKnown: Boolean
    val secure: Boolean
    val httpOnly: Boolean
}
