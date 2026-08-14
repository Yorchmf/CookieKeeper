/**
 * Typed client for the cross-site ("All Sites") consent roll-up:
 *   GET /api/v1/analytics/accounts/rollup — consent aggregated over every ACTIVE site the account owns
 *
 * The account is the JWT principal — there is no site id in the path, so nothing to smuggle in. The
 * endpoint is gated to the Pro/Business plan server-side (403 `CROSS_SITE_ANALYTICS_NOT_ENTITLED`); the
 * dashboard maps that code to a localized upgrade prompt. Every figure is aggregated from our own data
 * (`consent_events`) — never per-visitor PII (no `ipHash`, no user agent reaches the browser).
 */
import { apiFetch } from "@/lib/api";
import {
  type AnalyticsFilter,
  type AnalyticsRange,
  type ConsentAnalytics,
  type PeriodSummary,
  buildAnalyticsParams,
} from "@/lib/api/analytics";

/** The cross-site roll-up (backend `AccountAnalyticsResponse`). Consent-only in Slice A. */
export interface AccountAnalytics {
  range: AnalyticsRange;
  consent: ConsentAnalytics;
  /** Prior-window consent baseline for period-over-period deltas; null when none is comparable. */
  previous: PeriodSummary | null;
  /** Number of ACTIVE sites folded into the roll-up (0 for a brand-new or fully-archived account). */
  siteCount: number;
}

/** Fetch the account-wide consent roll-up within the (optional) window. */
export async function getAccountAnalytics(
  filter: AnalyticsFilter,
): Promise<AccountAnalytics> {
  const query = buildAnalyticsParams(filter).toString();
  const { data } = await apiFetch<AccountAnalytics>(
    `/api/v1/analytics/accounts/rollup${query ? `?${query}` : ""}`,
  );
  return data;
}
