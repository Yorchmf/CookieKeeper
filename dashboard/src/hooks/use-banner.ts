"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  copyBannerConfig,
  getBannerConfig,
  updateBannerConfig,
  type BannerConfigUpdateInput,
} from "@/lib/api/banner";

export const BANNER_QUERY_KEY = ["banner"] as const;

/** The site's current published banner config, or null when none exists yet (customizer page). */
export function useBannerConfig(siteId: string) {
  return useQuery({
    queryKey: [...BANNER_QUERY_KEY, siteId],
    queryFn: () => getBannerConfig(siteId),
  });
}

/** Publish a new banner version, then refresh the cached current config. */
export function useUpdateBannerConfig(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: BannerConfigUpdateInput) =>
      updateBannerConfig(siteId, input),
    onSuccess: (data) => {
      // Seed the cache with the freshly published version so the editor re-syncs without a refetch.
      queryClient.setQueryData([...BANNER_QUERY_KEY, siteId], data);
    },
  });
}

/**
 * Apply this site's banner to other sites. Each target gets a brand-new version, so their cached
 * configs are stale afterwards — invalidated by target id rather than seeded, because the response
 * carries the id list, not each target's new version number.
 */
export function useCopyBannerConfig(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (targetSiteIds: string[]) =>
      copyBannerConfig(siteId, targetSiteIds),
    onSuccess: (result) => {
      for (const targetId of result.copiedToSiteIds) {
        void queryClient.invalidateQueries({
          queryKey: [...BANNER_QUERY_KEY, targetId],
        });
      }
    },
  });
}
