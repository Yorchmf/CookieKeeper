import type { ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { DownloadEvidencePackButton } from "@/components/analytics/download-evidence-pack-button";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor keeps the LockedFeature upgrade link assertable.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
  usePathname: () => "/analytics",
  useRouter: () => ({ replace: vi.fn() }),
}));

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

const addBreadcrumb = vi.fn();
vi.mock("@sentry/nextjs", () => ({
  addBreadcrumb: (...args: unknown[]) => addBreadcrumb(...args),
}));

const SITE_ID = "11111111-2222-3333-4444-555555555555";

function entitlement(csvExport: boolean): { data: Entitlement; isPending: boolean } {
  return {
    isPending: false,
    data: {
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
    },
  };
}

function renderButton() {
  return render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <DownloadEvidencePackButton siteId={SITE_ID} />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  useEntitlement.mockReset();
  addBreadcrumb.mockReset();
});

describe("DownloadEvidencePackButton", () => {
  test("a non-Business account sees a locked upsell, not a working download", () => {
    useEntitlement.mockReturnValue(entitlement(false));

    renderButton();

    expect(screen.getByText("The compliance evidence pack is available on the Business plan.")).toBeDefined();
    expect(screen.getByRole("link", { name: /Upgrade/ }).getAttribute("href")).toBe("/billing");
    // No confirm download link leaks through the gate.
    expect(screen.queryByRole("link", { name: /Download ZIP/ })).toBeNull();
  });

  test("while the entitlement resolves, the trigger is busy and the gate stays closed", () => {
    useEntitlement.mockReturnValue({ data: undefined, isPending: true });

    renderButton();

    const trigger = screen.getByRole("button", { name: /Evidence pack/ });
    expect(trigger.getAttribute("aria-busy")).toBe("true");
    expect(screen.queryByText(/available on the Business plan/)).toBeNull();
  });

  test("a Business account confirms, then downloads the site's pack and logs one breadcrumb", async () => {
    useEntitlement.mockReturnValue(entitlement(true));

    renderButton();

    // The confirmation stands between the click and the download — the link is not present until opened.
    expect(screen.queryByRole("link", { name: /Download ZIP/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Evidence pack/ }));

    const confirm = await screen.findByRole("link", { name: /Download ZIP/ });
    expect(confirm.getAttribute("href")).toBe(
      `/api/v1/sites/${SITE_ID}/analytics/evidence-pack.zip`,
    );
    expect(confirm.hasAttribute("download")).toBe(true);

    // The anchor would trigger a real navigation in happy-dom; prevent it so only our side effects run.
    confirm.addEventListener("click", (event) => event.preventDefault());
    fireEvent.click(confirm);

    // Exactly one feature-tagged breadcrumb, and it carries no site id or PII.
    expect(addBreadcrumb).toHaveBeenCalledTimes(1);
    const crumb = addBreadcrumb.mock.calls[0][0] as Record<string, unknown>;
    expect(crumb.category).toBe("analytics.evidence_pack");
    expect(JSON.stringify(crumb)).not.toContain(SITE_ID);
  });
});
