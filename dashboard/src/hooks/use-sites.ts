"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  archiveSite,
  createSite,
  getSite,
  listSites,
  updateSite,
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

export function useUpdateSite(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { domain?: string }) => updateSite(id, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: SITES_QUERY_KEY });
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
