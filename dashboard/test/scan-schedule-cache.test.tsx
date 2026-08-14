import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useScanSchedule } from "@/hooks/use-scans";
import { useArchiveSite, useRestoreSite } from "@/hooks/use-sites";

// Mock the API clients, not the hooks: the real TanStack Query wiring then decides what refetches,
// which is the whole point of this test.
const getScanSchedule = vi.fn();
vi.mock("@/lib/api/scans", () => ({
  getScanSchedule: (siteId: string) => getScanSchedule(siteId),
  listScans: vi.fn(),
  getScan: vi.fn(),
  requestScan: vi.fn(),
}));

const archiveSite = vi.fn();
const restoreSite = vi.fn();
vi.mock("@/lib/api/sites", () => ({
  archiveSite: (id: string) => archiveSite(id),
  restoreSite: (id: string) => restoreSite(id),
  createSite: vi.fn(),
  getSite: vi.fn(),
  listSites: vi.fn(),
  setSiteBranding: vi.fn(),
  updateSite: vi.fn(),
  verifySite: vi.fn(),
}));

const SITE_ID = "site-1";

/**
 * Mounts the schedule query and one site mutation under one client, so the assertion is on the cache
 * contract between two separately-authored hooks rather than on either hook's internals.
 */
function renderPair(useMutationHook: typeof useArchiveSite) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(
    () => ({
      schedule: useScanSchedule(SITE_ID),
      mutation: useMutationHook(SITE_ID),
    }),
    { wrapper },
  );
}

beforeEach(() => {
  getScanSchedule.mockResolvedValue({
    scheduled: true,
    frequency: "weekly",
    nextScanAt: "2026-09-01T12:00:00Z",
    reason: null,
  });
  archiveSite.mockResolvedValue(undefined);
  restoreSite.mockResolvedValue({
    id: SITE_ID,
    domain: "shop.example.eu",
    status: "active",
  });
});

afterEach(() => {
  cleanup();
  getScanSchedule.mockReset();
  archiveSite.mockReset();
  restoreSite.mockReset();
});

describe("scan-schedule cache invalidation", () => {
  test("archiving a site refetches its schedule, which the job no longer honours", async () => {
    // `useArchiveSite` invalidates the `[scans, siteId]` prefix; the schedule key sits under it. If
    // the two keys ever drift apart, the card keeps promising a date for a site nothing revisits.
    const { result } = renderPair(useArchiveSite);
    await waitFor(() => expect(result.current.schedule.isSuccess).toBe(true));
    expect(getScanSchedule).toHaveBeenCalledTimes(1);

    result.current.mutation.mutate();

    await waitFor(() => expect(getScanSchedule).toHaveBeenCalledTimes(2));
  });

  test("restoring a site refetches its schedule, which is due again", async () => {
    const { result } = renderPair(useRestoreSite);
    await waitFor(() => expect(result.current.schedule.isSuccess).toBe(true));
    expect(getScanSchedule).toHaveBeenCalledTimes(1);

    result.current.mutation.mutate();

    await waitFor(() => expect(getScanSchedule).toHaveBeenCalledTimes(2));
  });
});
