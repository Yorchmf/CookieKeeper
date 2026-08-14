/** Typed client for the `/api/v1/sites/{siteId}/scans` read endpoints. */
import { apiFetch } from "@/lib/api";
import type { RescanFrequency } from "@/lib/api/billing";

/** Mirrors the backend ScanStatus dbValues (ScanEntity.kt). */
export type ScanStatus = "queued" | "running" | "done" | "failed";

/** Mirrors the backend ScanTrigger dbValues (ScanEntity.kt). */
export type ScanTrigger = "site_added" | "manual" | "scheduled";

/** A scan as it appears in a site's history — status/counts only (ScanSummaryResponse). */
export interface ScanSummary {
  id: string;
  status: ScanStatus;
  trigger: ScanTrigger;
  pagesCrawled: number | null;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
  createdAt: string;
  /**
   * How many cookie names this scan found that the previous completed scan on the same page did not —
   * the "+N new" history badge. Null when there is nothing to compare against on the page (a non-`done`
   * scan, or the oldest `done` scan whose predecessor is off the page); the scan detail view carries the
   * authoritative diff.
   */
  newCookieCount: number | null;
}

/** One observed cookie (ScanCookieResponse). `category`/`provider` are null until classified. */
export interface ScanCookie {
  name: string;
  domain: string | null;
  expiry: string | null;
  category: string | null;
  provider: string | null;
  isKnown: boolean;
}

/** Severity of a compliance issue (ComplianceIssue.severity), most-to-least severe. */
export type ComplianceSeverity = "critical" | "warning" | "info";

/**
 * One machine-coded compliance finding (ComplianceIssue). `code` is a stable token the dashboard
 * localizes via `scans.compliance.issues.{code}`; `count` is the number of cookies behind it. The
 * backend emits no prose — all wording is chosen here (i18n constraint).
 */
export interface ComplianceIssue {
  code: string;
  severity: ComplianceSeverity;
  count: number;
}

/**
 * The indicative compliance report for a completed scan (ComplianceReport). `score` is 0–100,
 * `issues` is ordered most-severe first. Present only for a `done` scan; null otherwise.
 */
export interface ComplianceReport {
  score: number;
  issues: ComplianceIssue[];
}

/**
 * How this scan's findings changed since the previous completed scan of the same site (ScanDiffResponse).
 * `hasPrevious` is false for the site's first completed scan (the lists are empty and the UI shows no
 * comparison). Cookies are compared by name; `trackerCountDelta` is a signed count delta (null with no
 * baseline) because raw tracker hosts are never stored.
 *
 * `addedCookieNames` and `removedCookieNames` are the backend's set differences (current − previous and
 * vice-versa), so each list holds unique names — the UI can safely key list items by name.
 */
export interface ScanDiff {
  hasPrevious: boolean;
  previousScanId: string | null;
  previousScanAt: string | null;
  newCookieCount: number;
  removedCookieCount: number;
  addedCookieNames: string[];
  removedCookieNames: string[];
  trackerCountDelta: number | null;
}

/**
 * A scan plus its cookies (ScanDetailResponse). `cookiesByCategory` is keyed by the backend's
 * canonical consent-category token (necessary/preferences/statistics/marketing) — the UI localizes
 * the key. `needsReview` holds cookies the signature DB did not recognize. `compliance` is the
 * indicative score/issue report, populated only once the scan is `done`. `diff` is how the findings
 * changed since the previous completed scan, also only once `done`.
 */
export interface ScanDetail extends ScanSummary {
  cookiesByCategory: Record<string, ScanCookie[]>;
  needsReview: ScanCookie[];
  compliance: ComplianceReport | null;
  diff: ScanDiff | null;
}

export interface ScansList {
  scans: ScanSummary[];
  total: number;
}

export async function listScans(
  siteId: string,
  limit?: number,
): Promise<ScansList> {
  const query = limit ? `?limit=${limit}` : "";
  const { data, meta } = await apiFetch<ScanSummary[]>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/scans${query}`,
  );
  return { scans: data, total: meta?.total ?? data.length };
}

/** Acknowledgement of an accepted re-scan (ScanRequestedResponse) — the queued scan's id and status. */
export interface ScanRequested {
  scanId: string;
  status: ScanStatus;
}

/**
 * Enqueue an on-demand re-scan for a site (Pro/Business). Returns only the queued scan's id — the
 * dashboard invalidates its scan list and lets the existing 3s poll surface progress. The backend
 * enforces the entitlement (403 `ON_DEMAND_RESCAN_NOT_ENTITLED`) and the single-in-flight rule
 * (409 `SCAN_ALREADY_IN_PROGRESS`); both surface as an `ApiError` the caller handles.
 */
export async function requestScan(siteId: string): Promise<ScanRequested> {
  const { data } = await apiFetch<ScanRequested>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/scans`,
    { method: "POST" },
  );
  return data;
}

/** Why the job would never come back to a site — the `reason` on an unscheduled answer. */
export type UnscheduledReason = "archived" | "lapsed" | "trial_ends_first";

/**
 * When the nightly re-scan job will next come back to this site (ScanScheduleResponse).
 *
 * `scheduled` is false when the job would never pick the site up, and `reason` says which case applies
 * so the UI can explain it rather than promising a scan that never runs. `nextScanAt` is null for a
 * never-scanned site (due immediately, so there is no date), and may be in the past for a site that is
 * already due and waiting for an upcoming nightly run.
 *
 * `frequency` and `reason` are typed as closed unions but arrive as open backend strings, so treat an
 * unrecognized value as "no answer" rather than assuming a message key exists for it.
 */
export interface ScanSchedule {
  scheduled: boolean;
  frequency: RescanFrequency | null;
  nextScanAt: string | null;
  reason: UnscheduledReason | null;
}

export async function getScanSchedule(siteId: string): Promise<ScanSchedule> {
  const { data } = await apiFetch<ScanSchedule>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/scan-schedule`,
  );
  return data;
}

export async function getScan(
  siteId: string,
  scanId: string,
): Promise<ScanDetail> {
  const { data } = await apiFetch<ScanDetail>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/scans/${encodeURIComponent(scanId)}`,
  );
  return data;
}
