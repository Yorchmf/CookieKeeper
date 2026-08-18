import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { DownloadEvidencePackButton } from "@/components/analytics/download-evidence-pack-button";
import { ExportAnalyticsButton } from "@/components/analytics/export-analytics-button";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load (unavailable under happy-dom); a plain
// anchor keeps the LockedFeature upgrade link assertable.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
}));

// The evidence-pack button imports Sentry at module load; stub it so the test doesn't drag in the SDK.
vi.mock("@sentry/nextjs", () => ({ addBreadcrumb: vi.fn() }));

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

function entitlement(csvExport: boolean): Entitlement {
  return {
    state: "subscribed",
    plan: csvExport ? "BUSINESS" : "STARTER",
    trialEndsAt: null,
    activeSites: 1,
    consentEventsUsed: null,
    limits: {
      maxSites: 3,
      rescanFrequency: "weekly",
      onDemandRescan: true,
      priorityScan: false,
      removeBranding: true,
      csvExport,
      crossSiteAnalytics: csvExport,
      consentRetentionMonths: 36,
      consentEventCap: null,
    },
  };
}

function renderExport() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <ExportAnalyticsButton siteId="site-1" filter={{ from: "2026-08-01T00:00:00Z" }} />
    </NextIntlClientProvider>,
  );
}

function renderEvidencePack() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <DownloadEvidencePackButton siteId="site-1" />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  useEntitlement.mockReset();
});

// The bug this fixes: a *failed* entitlement query was falling through to the "not entitled" branch,
// so a paying customer would be told to upgrade to Business over a transient network error.
describe.each([
  { name: "ExportAnalyticsButton", renderButton: renderExport, label: /export csv/i },
  { name: "DownloadEvidencePackButton", renderButton: renderEvidencePack, label: /evidence pack/i },
])("$name entitlement gate", ({ renderButton, label }) => {
  test("a failed entitlement query shows a retry, not the upgrade prompt", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: true, data: undefined });

    renderButton();

    // Neutral 'couldn't verify' + retry — never "Business plan" / "Upgrade".
    expect(screen.getByText(/couldn't verify your plan/i)).toBeDefined();
    expect(screen.getByRole("button", { name: /retry/i })).toBeDefined();
    expect(screen.queryByText(/Business plan/i)).toBeNull();
    expect(screen.queryByRole("link", { name: /upgrade/i })).toBeNull();
  });

  test("a resolved-but-not-entitled account still sees the upgrade prompt", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(false) });

    renderButton();

    expect(screen.getByText(/Business plan/i)).toBeDefined();
    expect(screen.getByRole("link", { name: /upgrade/i }).getAttribute("href")).toBe("/billing");
    expect(screen.queryByText(/couldn't verify your plan/i)).toBeNull();
  });

  test("an entitled account sees the real, actionable control", () => {
    useEntitlement.mockReturnValue({ isPending: false, isError: false, data: entitlement(true) });

    renderButton();

    // The export button renders an <a download> (role link); the evidence-pack button renders a dialog
    // trigger (role button). Assert on the shared, visible label rather than a per-button role.
    expect(screen.getByText(label)).toBeDefined();
    expect(screen.queryByText(/couldn't verify your plan/i)).toBeNull();
    expect(screen.queryByRole("link", { name: /upgrade/i })).toBeNull();
  });
});
