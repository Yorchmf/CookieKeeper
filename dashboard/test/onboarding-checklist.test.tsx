import type { ReactNode } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { OnboardingChecklist } from "@/components/dashboard/onboarding-checklist";
import type { OnboardingProgress } from "@/lib/api/overview";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide; a plain
// anchor preserves the link semantics the CTA is asserted on.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => <a href={href}>{children}</a>,
}));

function renderChecklist(progress: OnboardingProgress) {
  return render(
    <NextIntlClientProvider locale="en" messages={en} timeZone="UTC">
      <OnboardingChecklist progress={progress} />
    </NextIntlClientProvider>,
  );
}

const NONE: OnboardingProgress = {
  addedSite: false,
  scanned: false,
  customisedBanner: false,
  verified: false,
};

afterEach(cleanup);

describe("OnboardingChecklist", () => {
  test("renders one row per step and reports zero progress for a brand-new account", () => {
    renderChecklist(NONE);

    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    // The progressbar carries a static accessible name plus a humanised value.
    const bar = screen.getByRole("progressbar", { name: "Setup progress" });
    expect(bar.getAttribute("aria-valuenow")).toBe("0");
    expect(bar.getAttribute("aria-valuemax")).toBe("4");
    expect(bar.getAttribute("aria-valuetext")).toBe("0 of 4 done");
  });

  test("marks the current step programmatically, not by colour alone", () => {
    renderChecklist({ addedSite: true, scanned: false, customisedBanner: false, verified: false });

    // The one not-done step the customer should do next is exposed via aria-current, and named for SR users.
    const current = screen.getByRole("listitem", { current: "step" });
    expect(current.textContent).toContain("Scan for cookies");
    expect(current.textContent).toContain("Current step");
  });

  test("the CTA points at the first incomplete step, skipping the ones already done", () => {
    // Site added and scanned; the next thing to do is customise the banner.
    renderChecklist({ addedSite: true, scanned: true, customisedBanner: false, verified: false });

    const bar = screen.getByRole("progressbar");
    expect(bar.getAttribute("aria-valuenow")).toBe("2");
    // Only one primary action, and it is the current step — not "Add your site", already done.
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0].textContent).toBe("Customise banner");
    expect(links[0].getAttribute("href")).toBe("/sites");
  });

  test("a completed step is announced to assistive tech, not conveyed by colour alone", () => {
    renderChecklist({ addedSite: true, scanned: false, customisedBanner: false, verified: false });

    // The first row carries the sr-only "Done" marker; later rows do not.
    const rows = screen.getAllByRole("listitem");
    expect(rows[0].textContent).toContain("Done");
    expect(rows[1].textContent).not.toContain("Done");
  });

  test("nothing left to do renders full progress and no call to action", () => {
    renderChecklist({ addedSite: true, scanned: true, customisedBanner: true, verified: true });

    expect(screen.getByRole("progressbar").getAttribute("aria-valuenow")).toBe("4");
    // The parent hides the checklist at this point, but the component must not dangle a dead CTA either.
    expect(screen.queryByRole("link")).toBeNull();
  });
});
