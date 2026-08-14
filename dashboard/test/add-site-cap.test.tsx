import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { AddSiteDialog } from "@/components/sites/add-site-dialog";
import type { Entitlement } from "@/lib/api/billing";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

const useEntitlement = vi.fn();
vi.mock("@/hooks/use-billing", () => ({
  useEntitlement: () => useEntitlement(),
}));

vi.mock("@/hooks/use-sites", () => ({
  useCreateSite: () => ({ mutateAsync: vi.fn(), isPending: false }),
}));

vi.mock("@/hooks/use-auth", () => ({
  useMe: () => ({ data: { email: "owner@example.eu" } }),
}));

function entitlement(activeSites: number, maxSites: number): Entitlement {
  return {
    state: "subscribed",
    plan: "PRO",
    trialEndsAt: null,
    activeSites,
    consentEventsUsed: 0,
    limits: {
      maxSites,
      rescanFrequency: "weekly",
    } as Entitlement["limits"],
  };
}

/** Renders the dialog and opens it — the cap notice lives inside the form. */
function openDialog() {
  render(
    <NextIntlClientProvider locale="en" messages={en}>
      <AddSiteDialog />
    </NextIntlClientProvider>,
  );
  fireEvent.click(
    screen.getByRole("button", { name: en.sites.addDialog.trigger }),
  );
}

const submitButton = () =>
  screen
    .getAllByRole("button", { name: en.sites.addDialog.submit })
    .find((button) => button.getAttribute("type") === "submit");

beforeEach(() => {
  useEntitlement.mockReset();
  useEntitlement.mockReturnValue({ data: entitlement(1, 5) });
});

afterEach(cleanup);

describe("AddSiteDialog plan cap", () => {
  test("says nothing about the cap while there is room to spare", () => {
    openDialog();

    expect(screen.queryByText(/all of them are in use/i)).toBeNull();
    expect(screen.queryByText(/last of the/i)).toBeNull();
    expect(submitButton()?.hasAttribute("disabled")).toBe(false);
  });

  test("warns on the last remaining site before it is spent", () => {
    useEntitlement.mockReturnValue({ data: entitlement(4, 5) });
    openDialog();

    expect(screen.getByText(/last of the 5 sites/i)).toBeTruthy();
    // A warning, not a block — the slot is still available.
    expect(submitButton()?.hasAttribute("disabled")).toBe(false);
  });

  test("explains the cap and offers an upgrade before the domain is even typed", () => {
    useEntitlement.mockReturnValue({ data: entitlement(5, 5) });
    openDialog();

    expect(screen.getByText(/all of them are in use/i)).toBeTruthy();
    const upgrade = screen.getByRole("link", {
      name: en.sites.addDialog.capUpgrade,
    });
    expect(upgrade.getAttribute("href")).toBe("/billing");
  });

  test("keeps the submit live at the cap, since the count can lag another tab", () => {
    // The backend holds the authority under an advisory lock. A cached count that missed a site
    // archived elsewhere must not lock someone out of a slot they actually have — so the notice
    // explains, and the button still submits (and would surface the 403 if the cap is real).
    useEntitlement.mockReturnValue({ data: entitlement(5, 5) });
    openDialog();

    const submit = submitButton();
    expect(submit?.hasAttribute("disabled")).toBe(false);
    // The reason travels with the button, so tabbing straight to it still carries the explanation.
    const describedBy = submit?.getAttribute("aria-describedby");
    expect(describedBy).toBeTruthy();
    expect(
      document.getElementById(describedBy as string)?.textContent,
    ).toMatch(/all of them are in use/i);
  });

  test("treats a single-site plan in singular, not as '1 sites'", () => {
    useEntitlement.mockReturnValue({ data: entitlement(1, 1) });
    openDialog();

    expect(
      screen.getByText(/plan includes one site and it is already in use/i),
    ).toBeTruthy();
  });

  test("says the trial ended rather than quoting a plan of zero sites", () => {
    // A lapsed account keeps the sites it created but its cap drops to 0, so the remainder goes
    // negative — "your plan includes 0 sites" would be nonsense where "your trial ended" is true.
    useEntitlement.mockReturnValue({ data: entitlement(2, 0) });
    openDialog();

    expect(screen.getByText(en.sites.addDialog.capExpired)).toBeTruthy();
    expect(screen.queryByText(/your plan includes/i)).toBeNull();
    expect(
      screen.getByRole("link", { name: en.sites.addDialog.capUpgrade }),
    ).toBeTruthy();
  });

  test("claims nothing and blocks nothing while the entitlement is still loading", () => {
    // No count yet — guessing a cap here would either nag or block a customer who has room.
    useEntitlement.mockReturnValue({ data: undefined });
    openDialog();

    expect(screen.queryByText(/all of them are in use/i)).toBeNull();
    expect(submitButton()?.hasAttribute("disabled")).toBe(false);
    expect(submitButton()?.getAttribute("aria-describedby")).toBeNull();
  });
});
