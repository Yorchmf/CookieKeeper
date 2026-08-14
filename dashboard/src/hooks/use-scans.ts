"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  getScan,
  getScanSchedule,
  listScans,
  requestScan,
  type ScanStatus,
} from "@/lib/api/scans";

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

/**
 * Enqueue an on-demand re-scan, then invalidate this site's scan-history query. That refetch surfaces
 * the freshly-queued row, at which point `useScans`'s existing 3s `refetchInterval` takes over polling
 * to `done` — no extra polling logic lives here.
 */
export function useRequestScan(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => requestScan(siteId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...SCANS_QUERY_KEY, siteId],
      });
    },
  });
}

/**
 * When the scheduler next returns to a site. Not polled: the date only moves when a *new* scan is
 * enqueued or the site's status changes, and both of those paths already invalidate the `[scans, siteId]`
 * prefix this key sits under. A scan progressing queued → done leaves its `createdAt`, and therefore this
 * date, unchanged.
 *
 * It does refetch on window focus, against the app-wide default: the answer also decays with nothing but
 * time passing (a due date drifts into the past, and the nightly job enqueues scans with no client
 * involvement), and the card compares the date against this query's own fetch timestamp. Left frozen on a
 * tab someone returns to the next morning, it would present yesterday as "next".
 */
export function useScanSchedule(siteId: string) {
  return useQuery({
    queryKey: [...SCANS_QUERY_KEY, siteId, "schedule"],
    queryFn: () => getScanSchedule(siteId),
    refetchOnWindowFocus: true,
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
