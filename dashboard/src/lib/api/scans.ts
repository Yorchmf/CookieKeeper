/** Typed client for the `/api/v1/sites/{siteId}/scans` read endpoints. */
import { apiFetch } from "@/lib/api";

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

/**
 * A scan plus its cookies (ScanDetailResponse). `cookiesByCategory` is keyed by the backend's
 * canonical consent-category token (necessary/preferences/statistics/marketing) — the UI localizes
 * the key. `needsReview` holds cookies the signature DB did not recognize.
 */
export interface ScanDetail extends ScanSummary {
  cookiesByCategory: Record<string, ScanCookie[]>;
  needsReview: ScanCookie[];
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

export async function getScan(
  siteId: string,
  scanId: string,
): Promise<ScanDetail> {
  const { data } = await apiFetch<ScanDetail>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/scans/${encodeURIComponent(scanId)}`,
  );
  return data;
}
