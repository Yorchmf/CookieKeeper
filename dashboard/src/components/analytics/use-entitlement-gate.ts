"use client";

import { useEntitlement } from "@/hooks/use-billing";
import type { EntitlementLimits } from "@/lib/api/billing";

/**
 * The four states a plan-gated control can be in. Splitting `error` out from `locked` is the whole
 * point of this helper: a *failed* entitlement fetch must never be rendered as "you don't have this
 * plan", which would tell a paying customer to upgrade over a transient network blip. `locked` means
 * the plan resolved and genuinely lacks the feature; `error` means the plan is unknown.
 */
export type EntitlementGateStatus = "pending" | "error" | "locked" | "entitled";

export interface EntitlementGate {
  status: EntitlementGateStatus;
  /** Re-run the entitlement query — wired to the error affordance's retry. */
  retry: () => void;
}

/**
 * Resolve the shared entitlement query into a gate decision for one boolean limit (e.g. `csvExport`
 * or `crossSiteAnalytics`). Consumers render a distinct control per status; only `entitled` exposes
 * the real action, and the backend still enforces the gate (403), so this is display-only.
 *
 * Every plan-gated control routes through here so the error-vs-locked distinction is made once,
 * not re-derived (and forgotten) at each call site.
 */
export function useEntitlementGate(
  select: (limits: EntitlementLimits) => boolean,
): EntitlementGate {
  const entitlement = useEntitlement();
  const retry = () => {
    void entitlement.refetch();
  };

  if (entitlement.isPending) return { status: "pending", retry };
  if (entitlement.isError) return { status: "error", retry };
  return { status: select(entitlement.data.limits) ? "entitled" : "locked", retry };
}
