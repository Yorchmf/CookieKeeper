/** Typed client for the `/api/v1/sites` endpoints. */
import { apiFetch } from "@/lib/api";

export type SiteStatus = "active" | "archived";

export interface Site {
  id: string;
  domain: string;
  siteKey: string;
  status: SiteStatus;
  verifiedAt: string | null;
  createdAt: string;
}

/** How a verified site proved domain control (SiteVerificationResponse.method / VerificationMethod). */
export type VerificationMethod = "snippet" | "dns_txt";

export interface SiteDetail extends Site {
  embedSnippet: string;
  /** Non-null only once verified — which method succeeded, for the "verified via …" copy. */
  verificationMethod: VerificationMethod | null;
  /** The DNS TXT record name to publish for the fallback method (`_complyr.{domain}`). */
  dnsRecordName: string;
  /** The DNS TXT record value to publish — the site key. */
  dnsRecordValue: string;
  /** The customer's saved preference to hide the "Powered by Complyr" credit. */
  hideBranding: boolean;
  /**
   * Whether the account's plan grants branding removal. The toggle only takes effect when this and
   * `hideBranding` are both true; the UI locks the control and points at billing when it's false.
   */
  brandingRemovalEntitled: boolean;
}

/**
 * One verification attempt's outcome (SiteVerificationResponse). A *miss* is a normal 200 with
 * `verified: false` and a machine `reason` the UI renders as inline instructions — never an error.
 */
export interface SiteVerification {
  verified: boolean;
  verifiedAt: string | null;
  method: VerificationMethod | null;
  reason: string | null;
}

export interface SitesList {
  sites: Site[];
  total: number;
}

export async function listSites(status?: SiteStatus): Promise<SitesList> {
  const query = status ? `?status=${status}` : "";
  const { data, meta } = await apiFetch<Site[]>(`/api/v1/sites${query}`);
  return { sites: data, total: meta?.total ?? data.length };
}

export async function createSite(domain: string): Promise<Site> {
  const { data } = await apiFetch<Site>("/api/v1/sites", {
    method: "POST",
    body: JSON.stringify({ domain }),
  });
  return data;
}

export async function getSite(id: string): Promise<SiteDetail> {
  const { data } = await apiFetch<SiteDetail>(
    `/api/v1/sites/${encodeURIComponent(id)}`,
  );
  return data;
}

export async function updateSite(
  id: string,
  input: { domain?: string },
): Promise<SiteDetail> {
  const { data } = await apiFetch<SiteDetail>(
    `/api/v1/sites/${encodeURIComponent(id)}`,
    {
      method: "PATCH",
      body: JSON.stringify(input),
    },
  );
  return data;
}

/**
 * Ask the backend to prove domain control now (snippet check, DNS TXT fallback). Resolves for both
 * outcomes — `verified: true` and a `verified: false` miss are both 200s; only transport/auth failures
 * reject. Callers surface a miss as persistent inline instructions, not a toast.
 */
export async function verifySite(id: string): Promise<SiteVerification> {
  const { data } = await apiFetch<SiteVerification>(
    `/api/v1/sites/${encodeURIComponent(id)}/verify`,
    { method: "POST" },
  );
  return data;
}

/**
 * Set the site's "hide the Powered by Complyr credit" preference. Returns the fresh site detail so the
 * caller can re-render from server truth (the effective branding still depends on the plan entitlement,
 * which the backend floors — this only records the customer's wish).
 */
export async function setSiteBranding(
  id: string,
  hideBranding: boolean,
): Promise<SiteDetail> {
  const { data } = await apiFetch<SiteDetail>(
    `/api/v1/sites/${encodeURIComponent(id)}/branding`,
    {
      method: "PATCH",
      body: JSON.stringify({ hideBranding }),
    },
  );
  return data;
}

export async function archiveSite(id: string): Promise<{ archived: boolean }> {
  const { data } = await apiFetch<{ archived: boolean }>(
    `/api/v1/sites/${encodeURIComponent(id)}`,
    { method: "DELETE" },
  );
  return data;
}
