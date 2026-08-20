import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";

import { BlockingVerification } from "@/components/scans/blocking-verification";
import type { BlockingVerification as BlockingVerificationData } from "@/lib/api/scans";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor keeps the "go fix your embed" link assertable without the Next.js router.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

/**
 * The post-install blocking panel (BACKLOG #19). Its whole reason to exist is that a customer who has
 * installed Complyr and still fires Google Analytics before consent is *worse off* than one with no
 * banner — the banner is a written claim their site does not honour. So the assertions here are about
 * the two things that make that fixable: the vendor is named, and the literal tag to write is shown
 * with the right consent category.
 */
function renderPanel(verification: Partial<BlockingVerificationData> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <BlockingVerification
        siteId="11111111-1111-1111-1111-111111111111"
        verification={{
          status: "unblocked",
          vendors: [
            {
              domain: "google-analytics.com",
              name: "Google Analytics",
              consentCategory: "statistics",
            },
          ],
          blockedScriptCount: 0,
          ...verification,
        }}
      />
    </NextIntlClientProvider>,
  );
}

afterEach(cleanup);

describe("BlockingVerification", () => {
  test("names the vendor that is still firing and the exact tag that would block it", () => {
    renderPanel();

    expect(screen.getByText("Google Analytics")).toBeTruthy();
    // The attribute names are code, never translated — a localized attribute is a broken install.
    expect(
      screen.getByText(
        '<script type="text/plain" data-complyr-category="statistics" data-src="https://google-analytics.com/…"></script>',
      ),
    ).toBeTruthy();
  });

  test("shows the consent category in the customer's language, not the wire key", () => {
    renderPanel({
      vendors: [
        {
          domain: "doubleclick.net",
          name: "Google Ads",
          consentCategory: "marketing",
        },
      ],
    });

    expect(screen.getByText("Needs: Marketing")).toBeTruthy();
  });

  test("gives each vendor its own copy affordance", () => {
    renderPanel({
      vendors: [
        {
          domain: "google-analytics.com",
          name: "Google Analytics",
          consentCategory: "statistics",
        },
        {
          domain: "doubleclick.net",
          name: "Google Ads",
          consentCategory: "marketing",
        },
      ],
    });

    expect(screen.getByRole("button", { name: "Copy tag for Google Analytics" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Copy tag for Google Ads" })).toBeTruthy();
  });

  test("a wrong site key is called out as its own problem, not as unblocked vendors", () => {
    renderPanel({ status: "wrong_site_key", vendors: [] });

    expect(
      screen.getByText("The snippet is using another site's key"),
    ).toBeTruthy();
    // No remediation tags: the fix is replacing the snippet, not tagging scripts.
    expect(screen.queryByText(/data-complyr-category/)).toBeNull();
    expect(
      screen.getByRole("link", { name: "View this site's snippet" }),
    ).toBeTruthy();
  });

  test("a clean result says so and reports how many scripts are tagged", () => {
    renderPanel({ status: "clean", vendors: [], blockedScriptCount: 3 });

    expect(screen.getByText("Nothing runs before consent")).toBeTruthy();
    expect(
      screen.getByText("3 scripts are tagged and waiting for consent."),
    ).toBeTruthy();
  });

  /** Historical scans predate the probe; an empty "we don't know" card on each would be pure noise. */
  test("renders nothing when the scan was never probed", () => {
    const { container } = render(
      <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
        <BlockingVerification
          siteId="11111111-1111-1111-1111-111111111111"
          verification={{
            status: "unknown",
            vendors: [],
            blockedScriptCount: null,
          }}
        />
      </NextIntlClientProvider>,
    );

    expect(container.firstChild).toBeNull();
  });
});
