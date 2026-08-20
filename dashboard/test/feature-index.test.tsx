import { existsSync } from "node:fs";
import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { FeatureIndex } from "@/components/features/feature-index";
import type { Entitlement } from "@/lib/api/billing";
import { FEATURE_GROUPS, featureHref } from "@/lib/features/catalog";
import de from "../messages/de.json";
import en from "../messages/en.json";
import es from "../messages/es.json";
import fr from "../messages/fr.json";
import it from "../messages/it.json";

// `@/i18n/navigation` resolves `next/navigation` at module load (unavailable under happy-dom); a plain
// anchor keeps every card's destination assertable by href. The rest props matter: Base UI's `render`
// merges the Button's `aria-label` into this element, which is the accessible name we assert on.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

const useSites = vi.fn();
vi.mock("@/hooks/use-sites", () => ({
  useSites: () => useSites(),
}));

function entitlement(unlocked: boolean): Entitlement {
  return {
    state: "subscribed",
    plan: unlocked ? "BUSINESS" : "STARTER",
    trialEndsAt: null,
    activeSites: 1,
    consentEventsUsed: null,
    limits: {
      maxSites: 3,
      rescanFrequency: "weekly",
      onDemandRescan: unlocked,
      priorityScan: unlocked,
      removeBranding: unlocked,
      csvExport: unlocked,
      crossSiteAnalytics: unlocked,
      consentRetentionMonths: 36,
      consentEventCap: null,
    },
  };
}

function sitesResult(ids: readonly string[]) {
  return {
    isPending: false,
    isError: false,
    data: { sites: ids.map((id) => ({ id })), total: ids.length },
  };
}

function renderIndex() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <FeatureIndex />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  useEntitlement.mockReset();
  useSites.mockReset();
});

describe("feature catalogue", () => {
  const keys = FEATURE_GROUPS.flatMap((group) => group.features.map((feature) => feature.key));

  test("feature keys are unique — a duplicate would collide as a React key and a message key", () => {
    expect(new Set(keys).size).toBe(keys.length);
  });

  // The page is the index *of* the product, so an added capability must come with copy in all five
  // locales — a missing key throws at render in next-intl, which would be a blank card in production.
  test.each([
    ["en", en],
    ["de", de],
    ["es", es],
    ["fr", fr],
    ["it", it],
  ])("every group and feature has %s copy", (_locale, messages) => {
    const catalogue = (messages as typeof en).features;
    for (const group of FEATURE_GROUPS) {
      expect(catalogue.groups[group.key as keyof typeof catalogue.groups]).toBeTruthy();
    }
    for (const key of keys) {
      const item = catalogue.items[key as keyof typeof catalogue.items];
      expect(item?.title).toBeTruthy();
      expect(item?.description).toBeTruthy();
    }
  });

  // Every "Open" is a promise that there is something to open. Route files are the only source of
  // truth for that, and a wrong suffix builds fine and 404s in production — as `/scans` did.
  test("every destination is a real route", () => {
    const appDir = `${process.cwd()}/src/app/[locale]/(app)`;
    for (const group of FEATURE_GROUPS) {
      for (const feature of group.features) {
        const route =
          feature.scope === "account" ? feature.path : `/sites/[siteId]${feature.path}`;
        expect(existsSync(`${appDir}${route}/page.tsx`), `${feature.key} → ${route}`).toBe(true);
      }
    }
  });

  test("a per-site feature deep-links only when we know the site", () => {
    const banner = { key: "bannerDesign", scope: "site" as const, path: "/banner" };
    expect(featureHref(banner, "site-1")).toBe("/sites/site-1/banner");
    expect(featureHref(banner, null)).toBe("/sites");
    expect(featureHref({ key: "billing", scope: "account", path: "/billing" }, "site-1")).toBe("/billing");
  });
});

describe("FeatureIndex", () => {
  test("a locked capability is listed with its upgrade path, not hidden", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(false) });
    useSites.mockReturnValue(sitesResult(["site-1"]));

    renderIndex();

    // The whole point of the page: the feature is still on it.
    expect(screen.getByText(en.features.items.csvExport.title)).toBeDefined();
    expect(screen.getAllByText(/included in the business plan/i).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("link", { name: /upgrade/i }).length).toBeGreaterThan(0);
    // ...and it is not silently openable.
    expect(screen.queryByRole("link", { name: `Open ${en.features.items.csvExport.title}` })).toBeNull();
  });

  test("a failed entitlement query offers a retry rather than an upgrade prompt", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: true, data: undefined });
    useSites.mockReturnValue(sitesResult(["site-1"]));

    renderIndex();

    expect(screen.getAllByRole("button", { name: /retry/i }).length).toBeGreaterThan(0);
    expect(screen.queryByRole("link", { name: /upgrade/i })).toBeNull();
  });

  test("an entitled account opens the gated feature like any other", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });
    useSites.mockReturnValue(sitesResult(["site-1"]));

    renderIndex();

    const link = screen.getByRole("link", { name: `Open ${en.features.items.csvExport.title}` });
    expect(link.getAttribute("href")).toBe("/sites/site-1/analytics");
    expect(screen.queryByRole("link", { name: /upgrade/i })).toBeNull();
  });

  test("one active site deep-links per-site features straight into that site", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });
    useSites.mockReturnValue(sitesResult(["site-1"]));

    renderIndex();

    const link = screen.getByRole("link", { name: `Open ${en.features.items.bannerDesign.title}` });
    expect(link.getAttribute("href")).toBe("/sites/site-1/banner");
    expect(screen.queryByText(en.features.hint.noSites)).toBeNull();
    expect(screen.queryByText(en.features.hint.manySites)).toBeNull();
  });

  test("several sites fall back to the sites list, and say so", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });
    useSites.mockReturnValue(sitesResult(["site-1", "site-2"]));

    renderIndex();

    const link = screen.getByRole("link", { name: `Open ${en.features.items.bannerDesign.title}` });
    expect(link.getAttribute("href")).toBe("/sites");
    expect(screen.getByText(en.features.hint.manySites)).toBeDefined();
  });

  test("no sites yet points at the sites list with the add-your-first hint", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });
    useSites.mockReturnValue(sitesResult([]));

    renderIndex();

    const link = screen.getByRole("link", { name: `Open ${en.features.items.cookieScan.title}` });
    expect(link.getAttribute("href")).toBe("/sites");
    expect(screen.getByText(en.features.hint.noSites)).toBeDefined();
  });

  test("account-scoped features never depend on a site being chosen", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });
    useSites.mockReturnValue(sitesResult([]));

    renderIndex();

    expect(
      screen.getByRole("link", { name: `Open ${en.features.items.billing.title}` }).getAttribute("href"),
    ).toBe("/billing");
  });
});
