import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { SitesList } from "@/components/sites/sites-list";
import type { SiteStatus } from "@/lib/api/sites";
import en from "../messages/en.json";

// The Add-site dialog is an unrelated, data-heavy widget; stub it so this test isolates the
// list's status filtering and empty states.
vi.mock("@/components/sites/add-site-dialog", () => ({
  AddSiteDialog: () => null,
}));

// Drive the list query directly — the archive/restore backend and the client are covered elsewhere.
const useSites = vi.fn();
vi.mock("@/hooks/use-sites", () => ({
  useSites: (status?: SiteStatus) => useSites(status),
}));

// URL-as-state: the filter reads `?status=` and writes it through the localized router.
let currentSearch = "";
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(currentSearch),
}));
vi.mock("@/i18n/navigation", () => ({
  usePathname: () => "/sites",
  useRouter: () => ({ replace }),
  Link: ({ children, ...rest }: { children: ReactNode }) => (
    <a {...rest}>{children}</a>
  ),
}));

function renderList() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <SitesList />
    </NextIntlClientProvider>,
  );
}

const activeSite = {
  id: "site-1",
  domain: "shop.example.eu",
  siteKey: "key-1",
  status: "active" as const,
  verifiedAt: "2026-08-01T00:00:00Z",
  createdAt: "2026-08-01T00:00:00Z",
};

beforeEach(() => {
  currentSearch = "";
  replace.mockReset();
  useSites.mockReset();
  useSites.mockReturnValue({
    isPending: false,
    isError: false,
    data: { sites: [activeSite], total: 1 },
  });
});

afterEach(cleanup);

describe("SitesList status filter", () => {
  test("defaults to the active view and lists active sites", () => {
    renderList();

    expect(useSites).toHaveBeenCalledWith("active");
    expect(screen.getByText("shop.example.eu")).toBeDefined();
    const [active] = screen.getAllByRole("radio");
    expect(active.getAttribute("aria-checked")).toBe("true");
  });

  test("reads the archived view from the URL and queries for it", () => {
    currentSearch = "status=archived";
    useSites.mockReturnValue({
      isPending: false,
      isError: false,
      data: { sites: [], total: 0 },
    });
    renderList();

    expect(useSites).toHaveBeenCalledWith("archived");
    // Archived-specific empty copy, not the "add your first site" prompt.
    expect(screen.getByText(en.sites.empty.archived.title)).toBeDefined();
  });

  test("selecting Archived writes the status to the URL", () => {
    renderList();

    fireEvent.click(screen.getByRole("radio", { name: "Archived" }));

    expect(replace).toHaveBeenCalledWith("/sites?status=archived", {
      scroll: false,
    });
  });

  test("returning to Active drops the param to keep the URL clean", () => {
    currentSearch = "status=archived";
    renderList();

    fireEvent.click(screen.getByRole("radio", { name: "Active" }));

    expect(replace).toHaveBeenCalledWith("/sites", { scroll: false });
  });

  test("the active empty state prompts adding a first site", () => {
    useSites.mockReturnValue({
      isPending: false,
      isError: false,
      data: { sites: [], total: 0 },
    });
    renderList();

    const region = screen.getByRole("main");
    expect(
      within(region).getByText(en.sites.empty.active.title),
    ).toBeDefined();
  });
});
