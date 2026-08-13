"use client";

import { useQuery } from "@tanstack/react-query";

import { type AnalyticsFilter, getSiteAnalytics } from "@/lib/api/analytics";

export const ANALYTICS_QUERY_KEY = ["analytics"] as const;

/**
 * Aggregated analytics summary for a site over the given window. The filter is part of the query key,
 * so changing the range starts a fresh query (react-query hashes the key structurally). This is a
 * read-only dashboard view: no mutations, standard stale-while-revalidate caching.
 */
export function useSiteAnalytics(siteId: string, filter: AnalyticsFilter) {
  return useQuery({
    queryKey: [...ANALYTICS_QUERY_KEY, siteId, filter],
    queryFn: () => getSiteAnalytics(siteId, filter),
  });
}
