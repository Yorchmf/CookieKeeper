"use client";

import { useQuery } from "@tanstack/react-query";
import { getWidgetStatus } from "@/lib/api/widget-status";

export const WIDGET_STATUS_QUERY_KEY = ["widget-status"] as const;

/**
 * The site's widget-status card. Not polled: the signal is day-grained, so there is nothing a timer
 * could catch that a refetch on focus (React Query's default) or the card's own "Check again" button
 * does not — and the customer who just pasted the snippet is exactly the one who will press it.
 */
export function useWidgetStatus(siteId: string) {
  return useQuery({
    queryKey: [...WIDGET_STATUS_QUERY_KEY, siteId],
    queryFn: () => getWidgetStatus(siteId),
  });
}
