"use client";

import { useQuery } from "@tanstack/react-query";

import type { AnalyticsFilter } from "@/lib/api/analytics";
import { getOverview } from "@/lib/api/overview";

export const OVERVIEW_QUERY_KEY = ["overview"] as const;

/**
 * The account overview behind the dashboard home. The filter is part of the query key, so changing the
 * window starts a fresh query (react-query hashes the key structurally). Read-only: no mutations,
 * standard stale-while-revalidate caching.
 */
export function useOverview(filter: AnalyticsFilter) {
  return useQuery({
    queryKey: [...OVERVIEW_QUERY_KEY, filter],
    queryFn: () => getOverview(filter),
  });
}
