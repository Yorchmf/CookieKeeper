/**
 * Typed client for the site analytics read:
 *   GET /api/v1/sites/{siteId}/analytics            — aggregated JSON summary
 *   GET /api/v1/sites/{siteId}/analytics/export.csv — Business-plan consent-trend CSV
 *
 * Every figure is aggregated server-side from our own data (`consent_events`, `scans`/`scan_cookies`,
 * `policies`) — never third-party telemetry, and never per-visitor PII (no `ipHash`, no user agent
 * reaches the browser). The CSV export enforces the Business-plan gate server-side (403); the dashboard
 * gate on it is display-only.
 */
import { apiFetch } from "@/lib/api";

/** Consent decision totals over the window (backend `ActionBreakdown`). */
export interface ActionBreakdown {
  acceptAll: number;
  rejectAll: number;
  custom: number;
}

/** One UTC day of the consent trend series (backend `ConsentTrendPoint`). `date` is `YYYY-MM-DD`. */
export interface ConsentTrendPoint {
  date: string;
  acceptAll: number;
  rejectAll: number;
  custom: number;
  total: number;
}

/** Per-category opt-in aggregate: [optIns] trues out of [decisions] events carrying the key. */
export interface CategoryOptIn {
  category: string;
  optIns: number;
  decisions: number;
  /** optIns / decisions in [0, 1]; 0 when no decision carried the key. */
  rate: number;
}

/** Visitor-language event count (backend `LanguageCount`). Empty `lang` means the event carried none. */
export interface LanguageCount {
  lang: string;
  count: number;
}

export interface ConsentAnalytics {
  totalEvents: number;
  byAction: ActionBreakdown;
  trend: ConsentTrendPoint[];
  categoryOptIn: CategoryOptIn[];
  languageSplit: LanguageCount[];
}

/**
 * The consent baseline for the window immediately before the displayed one (same length), for
 * period-over-period deltas (backend `PeriodSummary`). Null when no comparable window exists — a brand-new
 * account, or a prior window the backend omitted because it fell below the plan retention floor (ADR-16).
 */
export interface PeriodSummary {
  totalEvents: number;
  byAction: ActionBreakdown;
}

/** A cookie-taxonomy bucket count from the latest completed scan (backend `CategoryCount`). */
export interface CategoryCount {
  category: string;
  count: number;
}

/** Cookie inventory from the site's most recent completed scan (backend `CookieAnalytics`). */
export interface CookieAnalytics {
  scanId: string;
  scannedAt: string;
  total: number;
  byCategory: CategoryCount[];
  known: number;
  unknown: number;
  insecure: number;
  trackerCount: number;
}

/** Current published policy version and the languages it exposes (backend `PolicyAnalytics`). */
export interface PolicyAnalytics {
  version: number;
  publishedAt: string | null;
  languages: string[];
}

/** The resolved analytics window (both ISO-8601 instants; `to` is exclusive). */
export interface AnalyticsRange {
  from: string;
  to: string;
}

/** The full analytics summary for one site (backend `SiteAnalyticsResponse`). */
export interface SiteAnalytics {
  range: AnalyticsRange;
  consent: ConsentAnalytics;
  /** Prior-window consent baseline for period-over-period deltas; null when none is comparable. */
  previous: PeriodSummary | null;
  /** Absent (null) until the site has at least one completed scan. */
  cookies: CookieAnalytics | null;
  /** Absent (null) until the site has a published policy. */
  policy: PolicyAnalytics | null;
}

/**
 * Window filter (mirrors the backend `AnalyticsFilter`). `from` is inclusive, `to` exclusive; both are
 * ISO-8601 instants. Omit either to let the backend default the trailing 30 days ending now.
 */
export interface AnalyticsFilter {
  from?: string;
  to?: string;
}

/** Serialize the window into query params (empty fields dropped). Shared by the read and the CSV href. */
export function buildAnalyticsParams(filter: AnalyticsFilter): URLSearchParams {
  const params = new URLSearchParams();
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  return params;
}

/** Fetch the aggregated analytics summary for a site within the (optional) window. */
export async function getSiteAnalytics(
  siteId: string,
  filter: AnalyticsFilter,
): Promise<SiteAnalytics> {
  const query = buildAnalyticsParams(filter).toString();
  const { data } = await apiFetch<SiteAnalytics>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/analytics${query ? `?${query}` : ""}`,
  );
  return data;
}

/**
 * Same-origin path for the Business-plan consent-trend CSV export with the active window applied. Used
 * as an `<a download>` href so the browser streams the file straight from the backend (auth cookies
 * attach automatically).
 */
export function analyticsExportPath(siteId: string, filter: AnalyticsFilter): string {
  const query = buildAnalyticsParams(filter).toString();
  return `/api/v1/sites/${encodeURIComponent(siteId)}/analytics/export.csv${query ? `?${query}` : ""}`;
}

/**
 * Same-origin path for the Business-plan compliance evidence pack (ZIP: published policy, trailing 30
 * days of consent audit evidence, latest scan summary, manifest). Window-independent — the pack always
 * bundles the current published policy and a fixed trailing consent window — so it takes no filter. Used
 * as an `<a download>` href so the browser streams straight from the backend with auth cookies attached;
 * the backend enforces the Business gate (403) and ownership (404).
 */
export function evidencePackPath(siteId: string): string {
  return `/api/v1/sites/${encodeURIComponent(siteId)}/analytics/evidence-pack.zip`;
}
