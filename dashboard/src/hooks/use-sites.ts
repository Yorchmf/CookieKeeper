"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  archiveSite,
  createSite,
  getSite,
  listSites,
  restoreSite,
  setSiteBranding,
  updateSite,
  verifySite,
  type SiteDetail,
  type SiteStatus,
} from "@/lib/api/sites";

export const SITES_QUERY_KEY = ["sites"] as const;

export function useSites(status?: SiteStatus) {
  return useQuery({
    queryKey: status ? [...SITES_QUERY_KEY, { status }] : SITES_QUERY_KEY,
    queryFn: () => listSites(status),
  });
}

export function useSite(id: string) {
  return useQuery({
    queryKey: [...SITES_QUERY_KEY, id],
    queryFn: () => getSite(id),
  });
}

export function useCreateSite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (domain: string) => createSite(domain),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SITES_QUERY_KEY });
    },
  });
}

/**
 * Update a site's mutable fields (currently its domain). The PATCH echoes the full authoritative detail,
 * so we write it straight into this site's cache — the detail heading, verified/unverified badge, and the
 * rename card's seed all reflect the change immediately, with no stale window while a refetch is in flight.
 * The list query is invalidated separately so the sites index picks up the new domain on its next read.
 */
export function useUpdateSite(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { domain?: string }) => updateSite(id, input),
    onSuccess: (site: SiteDetail) => {
      queryClient.setQueryData([...SITES_QUERY_KEY, id], site);
      void queryClient.invalidateQueries({ queryKey: SITES_QUERY_KEY });
    },
  });
}

/**
 * Trigger a domain-verification attempt for a site. On success invalidate this site's detail query so
 * the verified/unverified badge and the verify card re-render from fresh server state — the mutation
 * result itself is only the attempt outcome, not the full site payload.
 */
export function useVerifySite(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => verifySite(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...SITES_QUERY_KEY, id] });
    },
  });
}

/**
 * Persist the site's "hide the Powered by Complyr credit" preference. Writes the returned fresh detail
 * straight into this site's query cache so the toggle reflects server truth immediately (the backend
 * echoes the stored preference and the plan entitlement) without a second round-trip.
 */
export function useSetSiteBranding(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (hideBranding: boolean) => setSiteBranding(id, hideBranding),
    onSuccess: (site: SiteDetail) => {
      queryClient.setQueryData([...SITES_QUERY_KEY, id], site);
    },
  });
}

export function useArchiveSite(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => archiveSite(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SITES_QUERY_KEY });
    },
  });
}

/**
 * Reactivate an archived site. Seeds this site's detail cache from the authoritative response so the
 * status badge and detail actions flip immediately, then invalidates the list so both the active and
 * archived filtered views drop/pick up the site on their next read.
 */
export function useRestoreSite(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => restoreSite(id),
    onSuccess: (site: SiteDetail) => {
      queryClient.setQueryData([...SITES_QUERY_KEY, id], site);
      void queryClient.invalidateQueries({ queryKey: SITES_QUERY_KEY });
    },
  });
}
