import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { TrialStrip } from "@/components/dashboard/trial-strip";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor preserves the link semantics the CTA is asserted on.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, className }: { href: string; children: ReactNode; className?: string }) => (
    <a href={href} className={className}>
      {children}
    </a>
  ),
}));

// The strip reads billing state through a TanStack Query hook; stub it so the component renders without
// a QueryClientProvider or a network call.
const entitlementResult = { data: undefined as Entitlement | undefined };
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => entitlementResult,
}));

// A fixed "now" so the days-left ceil math is deterministic across runs.
const NOW = "2026-08-10T00:00:00Z";

function renderStrip(data: Entitlement | undefined) {
  entitlementResult.data = data;
  return render(
    <NextIntlClientProvider locale="en" messages={en} now={new Date(NOW)} timeZone="UTC">
      <TrialStrip />
    </NextIntlClientProvider>,
  );
}

const TRIAL: Entitlement = {
  state: "trial",
  plan: null,
  // 10 days out from NOW — comfortably clear of the ending-soon threshold.
  trialEndsAt: "2026-08-20T00:00:00Z",
  activeSites: 1,
  consentEventsUsed: 200,
  limits: {
    maxSites: 1,
    rescanFrequency: "monthly",
    onDemandRescan: false,
    priorityScan: false,
    removeBranding: false,
    csvExport: false,
    crossSiteAnalytics: false,
    consentRetentionMonths: 12,
    consentEventCap: 1000,
  },
};

afterEach(() => {
  cleanup();
  entitlementResult.data = undefined;
});

describe("TrialStrip", () => {
  test("renders nothing while the entitlement is still loading", () => {
    const { container } = renderStrip(undefined);
    expect(container.firstChild).toBeNull();
  });

  test("renders nothing for a subscribed account — no self-serve upsell on the home page", () => {
    const { container } = renderStrip({
      ...TRIAL,
      state: "subscribed",
      plan: "BUSINESS",
      trialEndsAt: null,
      consentEventsUsed: null,
      limits: { ...TRIAL.limits, consentEventCap: null },
    });
    expect(container.firstChild).toBeNull();
  });

  test("shows the day count, a usage meter, and a link to billing while trialing", () => {
    renderStrip(TRIAL);

    // 10 days from NOW, ceil'd — the plural branch reads "# days left in your trial".
    expect(screen.getByText("10 days left in your trial")).toBeDefined();

    const meter = screen.getByRole("meter");
    expect(meter.getAttribute("aria-valuenow")).toBe("200");
    expect(meter.getAttribute("aria-valuemax")).toBe("1000");
    expect(meter.getAttribute("aria-valuetext")).toBe("200 of 1,000 consent events used");

    const link = screen.getByRole("link", { name: "See plans" });
    expect(link.getAttribute("href")).toBe("/billing");
  });

  test("a comfortable trial stays neutral — no warning icon, accent-toned meter", () => {
    // Urgency is asserted through the component's own contract (the icon test seam and the meter's
    // data-tone), not through vendor icon classes or Tailwind utilities that a refactor would churn.
    renderStrip(TRIAL);
    expect(screen.queryByTestId("trial-urgent-icon")).toBeNull();
    expect(screen.getByRole("meter").getAttribute("data-tone")).toBe("neutral");
  });

  test("escalates when the trial is nearly over, even with low usage", () => {
    // 2 days left (<= 3), usage well under the cap: strip escalates (icon) because time is short, but
    // the meter tone stays neutral — usage itself is not the problem here.
    renderStrip({ ...TRIAL, trialEndsAt: "2026-08-12T00:00:00Z" });

    expect(screen.getByText("2 days left in your trial")).toBeDefined();
    expect(screen.getByTestId("trial-urgent-icon")).toBeDefined();
    expect(screen.getByRole("meter").getAttribute("data-tone")).toBe("neutral");
  });

  test("trial ending today reads the =0 plural branch and escalates", () => {
    // trialEndsAt in the past → Math.max(0, …) floors to 0 → the ICU `=0 {Your trial ends today}` branch.
    renderStrip({ ...TRIAL, trialEndsAt: "2026-08-09T00:00:00Z" });

    expect(screen.getByText("Your trial ends today")).toBeDefined();
    expect(screen.getByTestId("trial-urgent-icon")).toBeDefined();
  });

  test("escalates the meter when usage is near the cap, even with days to spare", () => {
    // 10 days left but 90% of the cap consumed — the meter tone flips to urgent and the strip escalates.
    renderStrip({ ...TRIAL, consentEventsUsed: 900 });

    expect(screen.getByRole("meter").getAttribute("aria-valuenow")).toBe("900");
    expect(screen.getByRole("meter").getAttribute("data-tone")).toBe("urgent");
    expect(screen.getByTestId("trial-urgent-icon")).toBeDefined();
  });

  test("shows the day count but no meter for an uncapped trial", () => {
    // A trial on a plan without a consent-event cap: still counts down, but there's nothing to meter.
    renderStrip({ ...TRIAL, consentEventsUsed: null, limits: { ...TRIAL.limits, consentEventCap: null } });

    expect(screen.getByText("10 days left in your trial")).toBeDefined();
    expect(screen.queryByRole("meter")).toBeNull();
  });

  test("clamps the meter to the cap when usage exceeds it (the cap never blocks ingestion)", () => {
    renderStrip({ ...TRIAL, consentEventsUsed: 1500 });

    const meter = screen.getByRole("meter");
    // aria-valuenow is clamped to max so assistive tech never reports an out-of-range value.
    expect(meter.getAttribute("aria-valuenow")).toBe("1000");
    // The visible fill clamps to a full bar too — a guard on the compositor-driven scaleX.
    const fill = meter.firstElementChild as HTMLElement;
    expect(fill.style.transform).toBe("scaleX(1)");
  });

  test("expired trial shows the subscribe prompt and no usage meter", () => {
    renderStrip({
      ...TRIAL,
      state: "expired",
      trialEndsAt: null,
      consentEventsUsed: null,
      limits: { ...TRIAL.limits, maxSites: 0, consentEventCap: null },
    });

    expect(
      screen.getByText("Your trial has ended. Subscribe to add sites and run scans again."),
    ).toBeDefined();
    expect(screen.queryByRole("meter")).toBeNull();
    expect(screen.getByRole("link", { name: "See plans" }).getAttribute("href")).toBe("/billing");
  });
});
