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

export interface SiteDetail extends Site {
  embedSnippet: string;
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

export async function archiveSite(id: string): Promise<{ archived: boolean }> {
  const { data } = await apiFetch<{ archived: boolean }>(
    `/api/v1/sites/${encodeURIComponent(id)}`,
    { method: "DELETE" },
  );
  return data;
}
