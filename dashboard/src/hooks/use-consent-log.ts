"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { listConsentEvents, type ConsentLogFilters } from "@/lib/api/consent";

export const CONSENT_LOG_QUERY_KEY = ["consent-log"] as const;

/**
 * Keyset-paginated, newest-first consent log for a site. Each page carries the opaque `nextCursor`
 * the backend emits in `meta`; `getNextPageParam` feeds it back as the next `pageParam`. The filter
 * object is part of the query key, so changing a filter starts a fresh paginated query (react-query
 * hashes the key structurally, so filter field order does not matter).
 */
export function useConsentLog(siteId: string, filters: ConsentLogFilters) {
  return useInfiniteQuery({
    queryKey: [...CONSENT_LOG_QUERY_KEY, siteId, filters],
    queryFn: ({ pageParam }) => listConsentEvents(siteId, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}
