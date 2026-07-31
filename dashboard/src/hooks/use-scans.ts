"use client";

import { useQuery } from "@tanstack/react-query";
import { getScan, listScans, type ScanStatus } from "@/lib/api/scans";

export const SCANS_QUERY_KEY = ["scans"] as const;

/** Scan states still in flight — these warrant background polling until they settle. */
const IN_FLIGHT_STATUSES: readonly ScanStatus[] = ["queued", "running"];

/** How often to re-poll while a scan is still queued or running. */
const POLL_INTERVAL_MS = 3000;

/** Newest-first scan history for a site. Polls while any row is still in flight. */
export function useScans(siteId: string, limit?: number) {
  return useQuery({
    queryKey: limit
      ? [...SCANS_QUERY_KEY, siteId, { limit }]
      : [...SCANS_QUERY_KEY, siteId],
    queryFn: () => listScans(siteId, limit),
    refetchInterval: (query) =>
      query.state.data?.scans.some((scan) =>
        IN_FLIGHT_STATUSES.includes(scan.status),
      )
        ? POLL_INTERVAL_MS
        : false,
  });
}

/** A single scan's classified cookies, grouped for the results view. Polls until terminal. */
export function useScan(siteId: string, scanId: string) {
  return useQuery({
    queryKey: [...SCANS_QUERY_KEY, siteId, scanId],
    queryFn: () => getScan(siteId, scanId),
    refetchInterval: (query) =>
      query.state.data &&
      IN_FLIGHT_STATUSES.includes(query.state.data.status)
        ? POLL_INTERVAL_MS
        : false,
  });
}
