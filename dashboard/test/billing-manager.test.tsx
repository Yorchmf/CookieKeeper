import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { BillingManager } from "@/components/billing/billing-manager";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// The card reads its data through TanStack Query hooks; stub them so the component renders without a
// QueryClientProvider or a network call. Checkout/portal are inert here — this suite is about the
// trial consent-usage meter, not the mutations.
const entitlementResult = { isPending: false, isError: false, data: undefined as Entitlement | undefined };
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => entitlementResult,
  useCheckout: () => ({ mutateAsync: vi.fn(), isPending: false, variables: undefined }),
  usePortal: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

function renderManager(data: Entitlement) {
  entitlementResult.data = data;
  return render(
    <NextIntlClientProvider
      locale="en"
      messages={en}
      now={new Date("2026-08-10T00:00:00Z")}
      timeZone="UTC"
    >
      <BillingManager />
    </NextIntlClientProvider>,
  );
}

const TRIAL: Entitlement = {
  state: "trial",
  plan: null,
  trialEndsAt: "2026-08-20T00:00:00Z",
  activeSites: 1,
  consentEventsUsed: 250,
  limits: {
    maxSites: 1,
    rescanFrequency: "monthly",
    onDemandRescan: false,
    priorityScan: false,
    removeBranding: false,
    csvExport: false,
    consentRetentionMonths: 12,
    consentEventCap: 1000,
  },
};

afterEach(() => {
  cleanup();
  entitlementResult.data = undefined;
});

describe("BillingManager trial consent-usage meter", () => {
  test("shows a meter reflecting events used against the trial cap", () => {
    renderManager(TRIAL);

    const meter = screen.getByRole("meter");
    expect(meter.getAttribute("aria-valuenow")).toBe("250");
    expect(meter.getAttribute("aria-valuemax")).toBe("1000");
    // Numbers are locale-formatted through ICU (`{used, number}` / `{max, number}`).
    expect(screen.getByText(/250 of 1,000 consent events/)).toBeDefined();
  });

  test("clamps the bar to 100% when usage exceeds the cap (the cap never blocks ingestion)", () => {
    renderManager({ ...TRIAL, consentEventsUsed: 1500 });

    const meter = screen.getByRole("meter");
    // aria-valuenow is clamped to the max so assistive tech never reports an out-of-range value.
    expect(meter.getAttribute("aria-valuenow")).toBe("1000");
    // The bar fills via a compositor transform (scaleX), clamped to a full 1.0 at/over the cap.
    const bar = meter.firstElementChild as HTMLElement;
    expect(bar.style.transform).toBe("scaleX(1)");
  });

  test("hides the meter for a subscribed account (the cap is trial-only)", () => {
    renderManager({
      ...TRIAL,
      state: "subscribed",
      plan: "BUSINESS",
      trialEndsAt: null,
      consentEventsUsed: null,
      limits: { ...TRIAL.limits, consentEventCap: null },
    });

    expect(screen.queryByRole("meter")).toBeNull();
  });
});
