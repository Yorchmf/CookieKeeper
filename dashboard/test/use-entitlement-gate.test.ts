import { renderHook } from "@testing-library/react";
import { afterEach, describe, expect, test, vi } from "vitest";

import { useEntitlementGate } from "@/components/analytics/use-entitlement-gate";
import type { Entitlement, EntitlementLimits } from "@/lib/api/billing";

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

function limits(overrides: Partial<EntitlementLimits> = {}): EntitlementLimits {
  return {
    maxSites: 3,
    rescanFrequency: "weekly",
    onDemandRescan: true,
    priorityScan: false,
    removeBranding: true,
    csvExport: false,
    crossSiteAnalytics: false,
    consentRetentionMonths: 36,
    consentEventCap: null,
    ...overrides,
  };
}

function entitlement(overrides: Partial<EntitlementLimits> = {}): Entitlement {
  return {
    state: "subscribed",
    plan: "BUSINESS",
    trialEndsAt: null,
    activeSites: 1,
    consentEventsUsed: null,
    limits: limits(overrides),
  };
}

afterEach(() => {
  useEntitlement.mockReset();
});

describe("useEntitlementGate", () => {
  test("reports 'pending' while the entitlement query is loading", () => {
    useEntitlement.mockReturnValue({ isPending: true, isError: false, data: undefined });

    const { result } = renderHook(() => useEntitlementGate((l) => l.csvExport));

    expect(result.current.status).toBe("pending");
  });

  test("reports 'error' — never 'locked' — when the entitlement query fails", () => {
    // The whole point of the helper: a failed fetch must not be conflated with "not entitled",
    // which would tell a paying customer to upgrade over a transient blip.
    useEntitlement.mockReturnValue({ isPending: false, isError: true, data: undefined });

    const { result } = renderHook(() => useEntitlementGate((l) => l.csvExport));

    expect(result.current.status).toBe("error");
  });

  test("reports 'locked' when the plan resolved but lacks the selected limit", () => {
    useEntitlement.mockReturnValue({
      isPending: false,
      isError: false,
      data: entitlement({ csvExport: false }),
    });

    const { result } = renderHook(() => useEntitlementGate((l) => l.csvExport));

    expect(result.current.status).toBe("locked");
  });

  test("reports 'entitled' when the resolved plan carries the selected limit", () => {
    useEntitlement.mockReturnValue({
      isPending: false,
      isError: false,
      data: entitlement({ csvExport: true }),
    });

    const { result } = renderHook(() => useEntitlementGate((l) => l.csvExport));

    expect(result.current.status).toBe("entitled");
  });

  test("the selector picks the limit — the same query gates different features independently", () => {
    useEntitlement.mockReturnValue({
      isPending: false,
      isError: false,
      data: entitlement({ csvExport: true, crossSiteAnalytics: false }),
    });

    const csv = renderHook(() => useEntitlementGate((l) => l.csvExport));
    const cross = renderHook(() => useEntitlementGate((l) => l.crossSiteAnalytics));

    expect(csv.result.current.status).toBe("entitled");
    expect(cross.result.current.status).toBe("locked");
  });

  test("retry re-runs the entitlement query", () => {
    const refetch = vi.fn();
    useEntitlement.mockReturnValue({ isPending: false, isError: true, data: undefined, refetch });

    const { result } = renderHook(() => useEntitlementGate((l) => l.csvExport));
    result.current.retry();

    expect(refetch).toHaveBeenCalledOnce();
  });
});
