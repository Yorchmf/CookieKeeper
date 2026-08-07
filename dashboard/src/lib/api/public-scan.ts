/**
 * Typed client for the anonymous marketing-funnel endpoints under
 * `/api/v1/public-scan` (docs ADR-12). No auth, no owning site — a scan is
 * addressed only by the opaque token returned when it is requested.
 *
 * The teaser is free (counts only); the detailed report is email-gated. An
 * unknown/expired/honeypot token yields a 404 (`PUBLIC_SCAN_NOT_FOUND`) that
 * `apiFetch` surfaces as an {@link import("@/lib/api").ApiError}.
 */
import { apiFetch } from "@/lib/api";
import type { ComplianceReport, ScanCookie, ScanStatus } from "@/lib/api/scans";

/** A public-scan cookie has the same shape as an owned-scan cookie (PublicScanCookieResponse). */
export type PublicScanCookie = ScanCookie;

/**
 * The free headline once a scan is done (PublicScanVerdict). `cookiesByCategory` is keyed by the
 * backend's canonical consent-category token — the UI localizes the key. `compliance` is the same
 * indicative score/issue report the authenticated scan surfaces (codes + counts, never cookie
 * names) — it motivates the fix without giving away the email-gated per-cookie detail.
 */
export interface PublicScanVerdict {
  totalCookies: number;
  cookiesByCategory: Record<string, number>;
  needsReviewCount: number;
  compliance: ComplianceReport;
}

/** Ungated teaser: the poll `status`, plus the verdict once the scan reaches `done` (null before). */
export interface PublicScanTeaser {
  status: ScanStatus;
  domain: string;
  verdict: PublicScanVerdict | null;
}

/** The email-gated detailed report: the verdict plus the full per-cookie breakdown. */
export interface PublicScanReport {
  status: ScanStatus;
  domain: string;
  verdict: PublicScanVerdict;
  cookiesByCategory: Record<string, PublicScanCookie[]>;
  needsReview: PublicScanCookie[];
}

/** What POST /public-scan returns: the opaque read token to poll on (PublicScanCreatedResponse). */
export interface PublicScanCreated {
  token: string;
  status: ScanStatus;
}

/**
 * Request an anonymous scan of a domain. `website` is the honeypot decoy: the visible form keeps it
 * off-screen so a human leaves it blank; a bot that fills it gets a plausible-but-throwaway token
 * (the backend silently no-ops it). Sent as `""` for real submissions so the server treats it as blank.
 */
export async function requestPublicScan(input: {
  domain: string;
  website?: string;
}): Promise<PublicScanCreated> {
  const { data } = await apiFetch<PublicScanCreated>("/api/v1/public-scan", {
    method: "POST",
    body: JSON.stringify({ domain: input.domain, website: input.website ?? "" }),
  });
  return data;
}

/** Poll the ungated teaser for a scan by its opaque token. */
export async function getPublicScanTeaser(
  token: string,
): Promise<PublicScanTeaser> {
  const { data } = await apiFetch<PublicScanTeaser>(
    `/api/v1/public-scan/${encodeURIComponent(token)}`,
  );
  return data;
}

/** Capture the lead email and unlock the detailed report for a scan. */
export async function unlockPublicScanReport(
  token: string,
  email: string,
): Promise<PublicScanReport> {
  const { data } = await apiFetch<PublicScanReport>(
    `/api/v1/public-scan/${encodeURIComponent(token)}/report`,
    { method: "POST", body: JSON.stringify({ email }) },
  );
  return data;
}
