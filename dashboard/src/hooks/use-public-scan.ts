"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import {
  getPublicScanTeaser,
  requestPublicScan,
  unlockPublicScanReport,
} from "@/lib/api/public-scan";
import type { ScanStatus } from "@/lib/api/scans";

export const PUBLIC_SCAN_QUERY_KEY = ["public-scan"] as const;

/** Scan states still in flight — poll the teaser until the scan settles. */
const IN_FLIGHT_STATUSES: readonly ScanStatus[] = ["queued", "running"];

/** How often to re-poll the teaser while the scan is still queued or running. */
const POLL_INTERVAL_MS = 3000;

/**
 * Poll the ungated teaser for a scan token. Disabled until a token exists (before the visitor
 * submits a domain); polls every {@link POLL_INTERVAL_MS} while the scan is queued/running and stops
 * once it is done or failed. A 404 (unknown/expired/honeypot token) surfaces as the query's error.
 */
export function usePublicScanTeaser(token: string | null) {
  return useQuery({
    queryKey: [...PUBLIC_SCAN_QUERY_KEY, token],
    enabled: token !== null,
    queryFn: () => {
      if (token === null) {
        throw new Error("public scan token required");
      }
      return getPublicScanTeaser(token);
    },
    // The token is a secret capability; never keep it cached longer than the session needs it.
    gcTime: 0,
    refetchInterval: (query) =>
      query.state.data && IN_FLIGHT_STATUSES.includes(query.state.data.status)
        ? POLL_INTERVAL_MS
        : false,
  });
}

/** Enqueue an anonymous scan (with the honeypot decoy passed straight through). */
export function useRequestPublicScan() {
  return useMutation({ mutationFn: requestPublicScan });
}

/** Capture the lead email and unlock the detailed report for a scan token. */
export function useUnlockPublicScanReport() {
  return useMutation({
    mutationFn: (vars: { token: string; email: string }) =>
      unlockPublicScanReport(vars.token, vars.email),
  });
}
