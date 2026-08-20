import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";

import { CopyBannerCard } from "@/components/banner/copy-banner-card";
import type { Site } from "@/lib/api/sites";
import en from "../messages/en.json";

// Assert the success toast without mounting a <Toaster>.
const toastSuccess = vi.fn();
vi.mock("sonner", () => ({ toast: { success: (...a: unknown[]) => toastSuccess(...a) } }));

const useSites = vi.fn();
vi.mock("@/hooks/use-sites", () => ({ useSites: (status?: string) => useSites(status) }));

const mutateAsync = vi.fn();
const useCopyBannerConfig = vi.fn();
vi.mock("@/hooks/use-banner", () => ({
  useCopyBannerConfig: (siteId: string) => useCopyBannerConfig(siteId),
}));

const copy = en.banner.copy;

function site(id: string, domain: string): Site {
  return {
    id,
    domain,
    siteKey: `sk_${id}`,
    status: "active",
    verifiedAt: null,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

/** The list endpoint's real envelope — `{ sites, total }`, not a bare array. */
function sitesList(sites: Site[]) {
  return { data: { sites, total: sites.length } };
}

const SOURCE = site("site-1", "shop.example.com");
const OTHER_A = site("site-2", "blog.example.com");
const OTHER_B = site("site-3", "docs.example.com");

function renderCard() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <CopyBannerCard siteId={SOURCE.id} />
    </NextIntlClientProvider>,
  );
}

function openDialog() {
  renderCard();
  fireEvent.click(screen.getByRole("button", { name: copy.cta }));
}

const applyButton = () =>
  screen.getByRole("button", { name: new RegExp(`^Apply(?: to \\d+ sites?)?$`) });

beforeEach(() => {
  useSites.mockReset();
  useCopyBannerConfig.mockReset();
  mutateAsync.mockReset();
  toastSuccess.mockReset();
  useSites.mockReturnValue(sitesList([SOURCE, OTHER_A, OTHER_B]));
  mutateAsync.mockResolvedValue({ sourceVersion: 3, copiedToSiteIds: [OTHER_A.id] });
  useCopyBannerConfig.mockReturnValue({ mutateAsync, isPending: false });
});

afterEach(cleanup);

describe("CopyBannerCard", () => {
  test("renders nothing when this is the account's only active site", () => {
    // Starter and trial allow exactly one site — a control that could never do anything is not shown.
    useSites.mockReturnValue(sitesList([SOURCE]));
    const { container } = renderCard();

    expect(container.textContent).toBe("");
  });

  test("offers only the other active sites as targets, never the source itself", () => {
    openDialog();

    expect(screen.getByRole("checkbox", { name: OTHER_A.domain })).toBeTruthy();
    expect(screen.getByRole("checkbox", { name: OTHER_B.domain })).toBeTruthy();
    // Copying a site onto itself is meaningless; it is not offered.
    expect(screen.queryByRole("checkbox", { name: SOURCE.domain })).toBeNull();
  });

  test("asks the sites endpoint for active sites only, so archived ones are never offered", () => {
    openDialog();

    // Archived targets are rejected server-side; not listing them keeps the UI from promising a copy
    // that would fail the whole all-or-nothing request.
    expect(useSites).toHaveBeenCalledWith("active");
  });

  test("apply stays disabled until at least one target is picked", () => {
    openDialog();

    expect(applyButton().hasAttribute("disabled")).toBe(true);

    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_A.domain }));

    expect(applyButton().hasAttribute("disabled")).toBe(false);
    // The label counts what will actually change.
    expect(applyButton().textContent).toBe("Apply to 1 site");
  });

  test("applying sends the selected ids and confirms with the server's own count", async () => {
    mutateAsync.mockResolvedValue({
      sourceVersion: 3,
      copiedToSiteIds: [OTHER_A.id, OTHER_B.id],
    });
    openDialog();

    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_A.domain }));
    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_B.domain }));
    fireEvent.click(applyButton());

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith([OTHER_A.id, OTHER_B.id]));
    // Reported from the response, not the selection — the server is what decided what changed.
    await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith("Banner applied to 2 sites"));
  });

  test("unticking a target removes it from the request", async () => {
    openDialog();

    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_A.domain }));
    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_B.domain }));
    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_A.domain }));
    fireEvent.click(applyButton());

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith([OTHER_B.id]));
  });

  test("a failed copy is reported in the dialog and the selection survives for a retry", async () => {
    mutateAsync.mockRejectedValue(new Error("boom"));
    openDialog();

    fireEvent.click(screen.getByRole("checkbox", { name: OTHER_A.domain }));
    fireEvent.click(applyButton());

    // Generic fallback copy: an unmapped backend code must never surface raw.
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe(en.auth.errors.GENERIC),
    );
    // Nothing was applied (the copy is all-or-nothing), so the dialog stays put with the ticks intact.
    expect(
      (screen.getByRole("checkbox", { name: OTHER_A.domain }) as HTMLInputElement).checked,
    ).toBe(true);
  });

  test("the target list is a labelled group for assistive tech", () => {
    openDialog();

    expect(screen.getByRole("group", { name: copy.legend })).toBeTruthy();
  });
});
