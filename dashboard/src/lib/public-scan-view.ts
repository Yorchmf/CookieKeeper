/**
 * Pure presentation logic for the anonymous free-scan result surfaces (teaser + report). Kept out of
 * the components so it can be unit-tested without rendering: the Without/With comparison rows derived
 * from the compliance findings, and the first- vs third-party cookie split behind the report filter.
 */
import type { ComplianceIssue } from "@/lib/api/scans";

/** Static product-value rows always shown after any scan-derived rows (and the whole table when clean). */
export const STATIC_COMPARISON_ROWS = ["banner", "policy", "logs"] as const;

/**
 * Issue codes we have Without/With copy for. A code the backend emits that isn't here is skipped rather
 * than rendered blank — so a new [ComplianceAnalyzer] finding can ship before its marketing copy without
 * breaking the table. Membership only; ordering comes from the backend's most-severe-first issue list.
 */
const KNOWN_ISSUE_ROWS = new Set<string>([
  "pre_consent_tracking",
  "marketing_cookies",
  "long_lived_cookies",
  "insecure_cookies",
  "third_party_trackers",
  "unclassified_cookies",
]);

/**
 * Row keys for the comparison table: each detected issue we have copy for — in the backend's severity
 * order, de-duplicated defensively — then the always-on static value props. A clean scan yields an empty
 * `issueKeys` and shows only the static rows.
 */
export function deriveComparisonRows(issues: ComplianceIssue[]): {
  issueKeys: string[];
  staticKeys: readonly string[];
} {
  const issueKeys: string[] = [];
  for (const issue of issues) {
    if (KNOWN_ISSUE_ROWS.has(issue.code) && !issueKeys.includes(issue.code)) {
      issueKeys.push(issue.code);
    }
  }
  return { issueKeys, staticKeys: STATIC_COMPARISON_ROWS };
}

/**
 * Whether a cookie belongs to a domain other than the scanned site — a third-party cookie. A leading
 * dot (RFC 6265 domain attribute) is ignored and matching is case-insensitive; the scanned host and any
 * subdomain of it are first-party. A null cookie domain can't be proven third-party, so it's treated as
 * first-party (it stays visible under "All", hidden under "Third-party only").
 */
export function isThirdPartyCookie(
  cookieDomain: string | null,
  scannedDomain: string,
): boolean {
  if (!cookieDomain) return false;
  const cookie = cookieDomain.replace(/^\./, "").toLowerCase();
  const site = scannedDomain.replace(/^\./, "").toLowerCase();
  if (cookie === site) return false;
  return !cookie.endsWith(`.${site}`);
}
