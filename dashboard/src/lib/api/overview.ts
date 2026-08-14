/**
 * Typed client for the account overview read:
 *   GET /api/v1/overview — headline figures + the action list behind the dashboard home
 *
 * The account-level sibling of `analytics.ts` (which is per-site). Everything is aggregated server-side
 * from our own data (`consent_events`, `scans`/`scan_cookies`, `policies`); no per-visitor PII reaches the
 * browser. Billing state is deliberately NOT part of this payload — the home page reads that from
 * `useEntitlement()` so there is one source of truth for whether the account is trialing.
 */
import { apiFetch } from "@/lib/api";
import { type AnalyticsFilter, type AnalyticsRange, buildAnalyticsParams } from "@/lib/api/analytics";

/**
 * What a site needs from the customer, most severe first — the backend emits at most one per site and
 * returns them in this order. Used as an i18n key suffix, so it is a closed union, not a free string.
 */
export type OverviewActionKind =
  | "unverified"
  | "never_scanned"
  | "policy_missing"
  | "policy_stale"
  | "insecure_cookies";

/** One site needing attention (backend `OverviewAction`). */
export interface OverviewAction {
  kind: OverviewActionKind;
  siteId: string;
  domain: string;
  /**
   * Magnitude where one exists (the insecure-cookie count). The backend omits null fields (NON_NULL),
   * so this arrives absent for every other kind — narrow with `!= null`.
   */
  count: number | null;
}

/**
 * Cross-site headline figures (backend `OverviewHeadline`). Consent figures cover the resolved window;
 * cookie/scan figures are point-in-time from each site's latest completed scan.
 *
 * `acceptAllRate` is a share in [0, 1] and is absent when no decisions were recorded in the window —
 * "no data yet" must render differently from "nobody accepted". `lastScanAt` is absent until some site
 * has completed a scan.
 */
export interface OverviewHeadline {
  activeSites: number;
  consentEvents: number;
  acceptAllRate: number | null;
  cookiesFound: number;
  lastScanAt: string | null;
}

/**
 * First-run progress for the getting-started checklist (backend `OnboardingProgress`). Each flag is
 * account-wide — "has ANY active site reached this step" — so it is exact for the single-site accounts
 * onboarding targets. Every flag is false for an account with no sites; the checklist hides itself once
 * all four are true (derive that `&&` on the client — the payload carries no `complete` field).
 *
 * `verified` doubles as "widget embedded": snippet-on-homepage is itself a verification method and there
 * is no independent live-widget signal, so verification is the honest proxy for the embed step.
 */
export interface OnboardingProgress {
  addedSite: boolean;
  scanned: boolean;
  customisedBanner: boolean;
  verified: boolean;
}

/** The dashboard home payload (backend `AccountOverviewResponse`). */
export interface AccountOverview {
  range: AnalyticsRange;
  headline: OverviewHeadline;
  actions: OverviewAction[];
  onboarding: OnboardingProgress;
}

/** Fetch the account overview within the (optional) window. */
export async function getOverview(filter: AnalyticsFilter): Promise<AccountOverview> {
  const query = buildAnalyticsParams(filter).toString();
  const { data } = await apiFetch<AccountOverview>(
    `/api/v1/overview${query ? `?${query}` : ""}`,
  );
  return data;
}
