import type { ReactNode } from "react";
import { cleanup, render, screen, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { DashboardHome } from "@/components/dashboard/dashboard-home";
import type { AccountOverview, OverviewAction } from "@/lib/api/overview";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor keeps the link semantics the rows are asserted on.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// The trial strip has its own billing source of truth; an undefined entitlement makes it render nothing
// so these tests stay about the overview itself.
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => ({ data: undefined }),
}));

const useOverview = vi.fn();
vi.mock("@/hooks/use-overview", () => ({
  useOverview: (...args: unknown[]) => useOverview(...args),
}));

const RANGE = { from: "2026-07-13T00:00:00Z", to: "2026-08-12T00:00:00Z" };

function overview(partial: Partial<AccountOverview> = {}): AccountOverview {
  return {
    range: RANGE,
    headline: {
      activeSites: 2,
      consentEvents: 1240,
      acceptAllRate: 0.62,
      cookiesFound: 17,
      lastScanAt: "2026-08-10T09:00:00Z",
    },
    actions: [],
    // Default to a fully onboarded account so the headline/attention surface renders; the first-run tests
    // override this to drive the checklist.
    onboarding: { addedSite: true, scanned: true, customisedBanner: true, verified: true },
    ...partial,
  };
}

function action(partial: Partial<OverviewAction> & { kind: OverviewAction["kind"] }): OverviewAction {
  return { siteId: `site-${partial.kind}`, domain: "example.com", count: null, ...partial };
}

function renderHome() {
  return render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <DashboardHome />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  useOverview.mockReset();
});

describe("DashboardHome", () => {
  test("renders the cross-site headline figures", () => {
    useOverview.mockReturnValue({ isPending: false, isError: false, data: overview() });

    renderHome();

    expect(screen.getByText("1,240")).toBeDefined();
    expect(screen.getByText("62%")).toBeDefined();
    expect(screen.getByText("17")).toBeDefined();
  });

  test("shows an em dash rather than 0% when no decisions were recorded", () => {
    useOverview.mockReturnValue({
      isPending: false,
      isError: false,
      data: overview({
        headline: { ...overview().headline, consentEvents: 0, acceptAllRate: null },
      }),
    });

    renderHome();

    // 0% would be a claim about visitor behaviour we have no evidence for.
    expect(screen.getByText("—")).toBeDefined();
    expect(screen.queryByText("0%")).toBeNull();
  });

  test("keeps the server's severity order and never re-sorts", () => {
    const actions = [
      action({ kind: "unverified", siteId: "a", domain: "a.example" }),
      action({ kind: "policy_missing", siteId: "b", domain: "b.example" }),
      action({ kind: "insecure_cookies", siteId: "c", domain: "c.example", count: 3 }),
    ];
    useOverview.mockReturnValue({ isPending: false, isError: false, data: overview({ actions }) });

    renderHome();

    const rows = screen.getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("Verify this domain")).toBeDefined();
    expect(within(rows[1]).getByText("Publish a cookie policy")).toBeDefined();
    // The ICU plural renders the count the API measured, not a generic label.
    expect(within(rows[2]).getByText("3 insecure cookies")).toBeDefined();
  });

  test("each row links to the page where the problem is actually fixed", () => {
    useOverview.mockReturnValue({
      isPending: false,
      isError: false,
      data: overview({ actions: [action({ kind: "never_scanned", siteId: "s1" })] }),
    });

    renderHome();

    expect(screen.getByRole("link", { name: /Run the first scan/ }).getAttribute("href")).toBe(
      "/sites/s1/scans",
    );
  });

  test("an account with nothing outstanding gets an explicit all-clear, not a blank gap", () => {
    useOverview.mockReturnValue({ isPending: false, isError: false, data: overview() });

    renderHome();

    expect(screen.getByText("Everything looks compliant")).toBeDefined();
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
  });

  test("an account with no active sites gets the onboarding checklist instead of zeroed tiles", () => {
    useOverview.mockReturnValue({
      isPending: false,
      isError: false,
      data: overview({
        headline: {
          activeSites: 0,
          consentEvents: 0,
          acceptAllRate: null,
          cookiesFound: 0,
          lastScanAt: null,
        },
        onboarding: { addedSite: false, scanned: false, customisedBanner: false, verified: false },
      }),
    });

    renderHome();

    // The checklist is the first-run surface; before any site exists there are no figures to summarise.
    expect(screen.getByRole("heading", { name: "Get set up" })).toBeDefined();
    expect(screen.getByRole("link", { name: "Add your site" }).getAttribute("href")).toBe("/sites");
    expect(screen.queryByText("Active sites")).toBeNull();
    expect(screen.queryByText("Needs your attention")).toBeNull();
  });

  test("a part-way account still onboarding gets the checklist and headline, not the attention list", () => {
    useOverview.mockReturnValue({
      isPending: false,
      isError: false,
      data: overview({
        actions: [action({ kind: "unverified", siteId: "s1" })],
        // Site added and scanned, banner customised, but not yet verified — one step to go.
        onboarding: { addedSite: true, scanned: true, customisedBanner: true, verified: false },
      }),
    });

    renderHome();

    expect(screen.getByRole("heading", { name: "Get set up" })).toBeDefined();
    // Figures still render once a site exists, but the attention list stays hidden during onboarding.
    expect(screen.getByText("Active sites")).toBeDefined();
    expect(screen.queryByText("Needs your attention")).toBeNull();
    // The CTA points at the one remaining step, not back to the start.
    expect(screen.getByRole("link", { name: "Embed and verify" }).getAttribute("href")).toBe("/sites");
  });

  test("a failed load is announced, not silently rendered as an empty account", () => {
    useOverview.mockReturnValue({ isPending: false, isError: true, data: undefined });

    renderHome();

    expect(screen.getByRole("alert").textContent).toContain("couldn't load");
    expect(screen.queryByText("Get set up")).toBeNull();
  });

  test("the loading state marks the region busy so assistive tech doesn't read a half-built page", () => {
    useOverview.mockReturnValue({ isPending: true, isError: false, data: undefined });

    renderHome();

    expect(screen.getByRole("main").getAttribute("aria-busy")).toBe("true");
  });
});
