"use client";

import { useQuery } from "@tanstack/react-query";

import { type AnalyticsFilter } from "@/lib/api/analytics";
import { getAccountAnalytics } from "@/lib/api/account-analytics";

export const ACCOUNT_ANALYTICS_QUERY_KEY = ["account-analytics"] as const;

/**
 * Cross-site ("All Sites") consent roll-up over the given window. The filter is part of the query key,
 * so changing the range starts a fresh query. Read-only dashboard view: no mutations, standard
 * stale-while-revalidate caching.
 *
 * Pass `enabled: false` while the entitlement is unresolved or absent so a locked account never fires
 * the roll-up read (the backend would answer 403 anyway). When enabled, a 403
 * (`CROSS_SITE_ANALYTICS_NOT_ENTITLED`) surfaces as the query's error for the caller to handle.
 */
export function useAccountAnalytics(
  filter: AnalyticsFilter,
  options: { enabled?: boolean } = {},
) {
  return useQuery({
    queryKey: [...ACCOUNT_ANALYTICS_QUERY_KEY, filter],
    queryFn: () => getAccountAnalytics(filter),
    enabled: options.enabled ?? true,
  });
}
