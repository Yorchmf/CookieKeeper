import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { AccountAnalyticsView } from "@/components/analytics/account-analytics-view";
import type { AccountAnalytics } from "@/lib/api/account-analytics";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor keeps the LockedFeature upgrade link assertable.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
  usePathname: () => "/analytics",
  useRouter: () => ({ replace: vi.fn() }),
}));

// The range selector reads the URL; a stable empty param keeps the default 30-day window.
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(),
}));

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

const useAccountAnalytics = vi.fn();
vi.mock("@/hooks/use-account-analytics", () => ({
  useAccountAnalytics: (...args: unknown[]) => useAccountAnalytics(...args),
}));

function entitlement(crossSiteAnalytics: boolean): { data: Entitlement } {
  return {
    data: {
      state: "subscribed",
      plan: crossSiteAnalytics ? "PRO" : "STARTER",
      trialEndsAt: null,
      activeSites: 3,
      consentEventsUsed: null,
      limits: {
        maxSites: 3,
        rescanFrequency: "weekly",
        onDemandRescan: true,
        priorityScan: false,
        removeBranding: true,
        csvExport: false,
        crossSiteAnalytics,
        consentRetentionMonths: 36,
        consentEventCap: null,
      },
    },
  };
}

function rollup(partial: Partial<AccountAnalytics> = {}): AccountAnalytics {
  return {
    range: { from: "2026-07-13T00:00:00Z", to: "2026-08-12T00:00:00Z" },
    consent: {
      totalEvents: 200,
      byAction: { acceptAll: 130, rejectAll: 40, custom: 30 },
      // 200 decisions over 500 impressions → 40% interaction rate.
      impressions: 500,
      interactionRate: 0.4,
      trend: [],
      categoryOptIn: [],
      languageSplit: [{ lang: "en", count: 200 }],
    },
    previous: null,
    siteCount: 4,
    ...partial,
  };
}

function renderView() {
  return render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <AccountAnalyticsView />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  useEntitlement.mockReset();
  useAccountAnalytics.mockReset();
});

describe("AccountAnalyticsView", () => {
  test("a non-entitled account sees an upgrade prompt, not the dashboard or an empty page", () => {
    useEntitlement.mockReturnValue(entitlement(false));
    useAccountAnalytics.mockReturnValue({ isPending: true, isError: false, data: undefined });

    renderView();

    expect(screen.getByText("Cross-site analytics is a Pro and Business feature.")).toBeDefined();
    expect(screen.getByRole("link", { name: /Upgrade/ }).getAttribute("href")).toBe("/billing");
    // No range selector and no figures leak through the gate.
    expect(screen.queryByRole("radiogroup")).toBeNull();
  });

  test("an entitled account renders the cross-site roll-up with its site count", () => {
    useEntitlement.mockReturnValue(entitlement(true));
    useAccountAnalytics.mockReturnValue({
      isPending: false,
      isError: false,
      isSuccess: true,
      isFetching: false,
      data: rollup(),
    });

    renderView();

    expect(screen.getByText("All Sites")).toBeDefined();
    expect(screen.getByText("Active sites")).toBeDefined();
    expect(screen.getByText("4")).toBeDefined();
    // 130 / 200 = 65% accept-all.
    expect(screen.getByText("65%")).toBeDefined();
    // 500 banner impressions and 200 / 500 = 40% interaction rate.
    expect(screen.getByText("500")).toBeDefined();
    expect(screen.getByText("40%")).toBeDefined();
    expect(screen.getByRole("radiogroup")).toBeDefined();
    // No comparable prior window (previous: null) → no delta badges at all.
    expect(screen.queryByText(/pts$/)).toBeNull();
  });

  test("shows period-over-period deltas when a comparable prior window is present", () => {
    useEntitlement.mockReturnValue(entitlement(true));
    useAccountAnalytics.mockReturnValue({
      isPending: false,
      isError: false,
      isSuccess: true,
      isFetching: false,
      // Current: 200 events, 65% accept-all, 500 impressions (40% rate). Prior: 100 events, 50%
      // accept-all, 200 impressions (50% rate).
      data: rollup({
        previous: {
          totalEvents: 100,
          byAction: { acceptAll: 50, rejectAll: 30, custom: 20 },
          impressions: 200,
        },
      }),
    });

    renderView();

    // Accept-all rate 65% vs prior 50% → +15 percentage points.
    expect(screen.getByText("+15 pts")).toBeDefined();
    // Event volume 200 vs prior 100 → +100% relative change.
    expect(screen.getByText("+100%")).toBeDefined();
    // Impression volume 500 vs prior 200 → +150% relative change.
    expect(screen.getByText("+150%")).toBeDefined();
    // Interaction rate 40% vs prior 50% → −10 percentage points.
    expect(screen.getByText("−10 pts")).toBeDefined();
  });

  test("renders the flat and downward delta branches, not just the upward one", () => {
    useEntitlement.mockReturnValue(entitlement(true));
    useAccountAnalytics.mockReturnValue({
      isPending: false,
      isError: false,
      isSuccess: true,
      isFetching: false,
      // Current: 100 events, 50% accept-all, 500 impressions (20% rate). Prior: 200 events, also
      // 50% accept-all, 400 impressions (50% rate).
      data: rollup({
        consent: {
          totalEvents: 100,
          byAction: { acceptAll: 50, rejectAll: 30, custom: 20 },
          impressions: 500,
          interactionRate: 0.2,
          trend: [],
          categoryOptIn: [],
          languageSplit: [{ lang: "en", count: 100 }],
        },
        previous: {
          totalEvents: 200,
          byAction: { acceptAll: 100, rejectAll: 60, custom: 40 },
          impressions: 400,
        },
      }),
    });

    renderView();

    // Accept-all rate unchanged (50% vs 50%) → flat "0 pts" badge with the "unchanged" screen-reader sentence.
    expect(screen.getByText("0 pts")).toBeDefined();
    expect(screen.getByText("unchanged versus the previous 30 days")).toBeDefined();
    // Event volume 100 vs prior 200 → −50%, exercising the down glyph (U+2212) and the "down …" sentence.
    expect(screen.getByText("−50%")).toBeDefined();
    expect(screen.getByText("down 50 percent versus the previous 30 days")).toBeDefined();
    // Interaction rate 20% vs prior 50% → −30 pts; impression volume 500 vs prior 400 → +25%.
    expect(screen.getByText("−30 pts")).toBeDefined();
    expect(screen.getByText("+25%")).toBeDefined();
  });

  test("an entitled account with a failed load is told so, not shown an empty roll-up", () => {
    useEntitlement.mockReturnValue(entitlement(true));
    useAccountAnalytics.mockReturnValue({ isPending: false, isError: true, data: undefined });

    renderView();

    expect(screen.getByRole("alert").textContent).toContain("couldn't load");
    expect(screen.queryByText("Active sites")).toBeNull();
  });

  test("an entitlement fetch failure shows an error, not an upgrade prompt to a paying account", () => {
    useEntitlement.mockReturnValue({ data: undefined, isPending: false, isError: true });
    useAccountAnalytics.mockReturnValue({ isPending: true, isError: false, data: undefined });

    renderView();

    expect(screen.getByRole("alert").textContent).toContain("couldn't load");
    // A transient entitlement error must never be conflated with "not entitled".
    expect(screen.queryByText(/Pro and Business feature/)).toBeNull();
  });

  test("while the entitlement is still resolving, the gate stays closed (skeleton, no read)", () => {
    useEntitlement.mockReturnValue({ data: undefined, isPending: true });
    useAccountAnalytics.mockReturnValue({ isPending: true, isError: false, data: undefined });

    renderView();

    // Neither the upgrade prompt nor the dashboard — just the loading shell.
    expect(screen.queryByText(/Pro and Business feature/)).toBeNull();
    expect(screen.queryByText("Active sites")).toBeNull();
  });
});
