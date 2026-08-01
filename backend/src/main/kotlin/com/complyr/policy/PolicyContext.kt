package com.complyr.policy

import java.time.LocalDate

/**
 * The fully-resolved input to [PolicyRenderer] for a single language: the customer's business
 * details plus the site's classified cookies grouped for display. Purely a value object — building
 * it (loading settings + the latest scan's cookies) is the service's job; rendering it is the
 * renderer's. Keeping the two apart makes the renderer a pure, exhaustively testable function.
 */
data class PolicyContext(
    val companyName: String,
    val contactEmail: String,
    val websiteUrl: String,
    val address: String?,
    val updatedOn: LocalDate,
    /** Classified cookies keyed by canonical [com.complyr.banner.ConsentCategory] key. */
    val cookiesByCategory: Map<String, List<PolicyCookie>>,
    /** Cookies the scanner could not classify — listed in their own section so none are hidden. */
    val unclassified: List<PolicyCookie>,
) {
    /** True when the site has no detected cookies at all — the policy states this explicitly. */
    fun hasNoCookies(): Boolean = cookiesByCategory.values.all { it.isEmpty() } && unclassified.isEmpty()
}

/** One cookie as the policy lists it. All fields are display-only and escaped at render time. */
data class PolicyCookie(
    val name: String,
    val provider: String?,
    val expiry: String?,
    val domain: String?,
)
