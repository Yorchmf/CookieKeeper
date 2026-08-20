import type { ReactNode } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { DeleteAccountCard } from "@/components/settings/delete-account-card";
import { ExportDataCard } from "@/components/settings/export-data-card";
import { ApiError } from "@/lib/api";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// Stub the mutation so the cards render without a QueryClientProvider or a real erasure.
const mutateAsync = vi.fn();
vi.mock("@/hooks/use-account", () => ({
  useDeleteAccount: () => ({ mutateAsync, isPending: false }),
}));

function renderCard(card: ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      {card}
    </NextIntlClientProvider>,
  );
}

/** Opens the confirmation dialog and types `password` into it. */
async function openDialogWithPassword(password: string) {
  fireEvent.click(screen.getByRole("button", { name: /delete my account/i }));
  const field = await screen.findByLabelText(/password/i);
  fireEvent.change(field, { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: /delete permanently/i }));
}

afterEach(() => {
  cleanup();
  mutateAsync.mockReset();
});

describe("ExportDataCard", () => {
  test("offers the export as a same-origin download rather than a fetch", () => {
    renderCard(<ExportDataCard />);

    // Styled as a button but exposed as a *link* — it navigates, so it must keep the link role
    // (a `role="button"` here would drop it from a screen reader's links list). The download relies
    // on the browser sending the auth cookies with a plain navigation, so it stays a real anchor.
    const link = screen.getByRole("link", { name: /download json/i });
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("/api/v1/account/export.json");
    // `download` keeps the browser from navigating away from the dashboard.
    expect(link.hasAttribute("download")).toBe(true);
  });

  test("says where consent records live instead of implying the export holds them", () => {
    renderCard(<ExportDataCard />);

    expect(screen.getByText(/consent records belong to your visitors/i))
      .toBeDefined();
  });
});

describe("DeleteAccountCard", () => {
  test("discloses that consent evidence survives as an anonymous placeholder", () => {
    renderCard(<DeleteAccountCard />);

    // The whole point of ADR-20: never let the user believe erasure is total when it is not.
    expect(screen.getByText(/what we cannot erase/i)).toBeDefined();
    expect(screen.getByText(/empty placeholder/i)).toBeDefined();
    expect(screen.getByText(/three years/i)).toBeDefined();
  });

  test("an empty password never reaches the erasure endpoint", async () => {
    renderCard(<DeleteAccountCard />);

    fireEvent.click(screen.getByRole("button", { name: /delete my account/i }));
    await screen.findByLabelText(/password/i);
    fireEvent.click(screen.getByRole("button", { name: /delete permanently/i }));

    // Exact match: the dialog description opens with the same sentence.
    expect(await screen.findByText("Enter your password to confirm"))
      .toBeDefined();
    expect(mutateAsync).not.toHaveBeenCalled();
  });

  test("a confirmed deletion reports what happened to each site", async () => {
    mutateAsync.mockResolvedValue({ sitesDeleted: 2, sitesAnonymized: 1 });
    renderCard(<DeleteAccountCard />);

    await openDialogWithPassword("correct horse battery staple");

    await waitFor(() =>
      expect(mutateAsync).toHaveBeenCalledWith("correct horse battery staple"),
    );
    expect(await screen.findByText(/your account has been deleted/i))
      .toBeDefined();
    expect(screen.getByText(/2 sites were deleted completely/i)).toBeDefined();
    expect(
      screen.getByText(/1 site was kept as an anonymous placeholder/i),
    ).toBeDefined();
  });

  test("a rejected password keeps the account and explains why", async () => {
    mutateAsync.mockRejectedValue(
      new ApiError("Password confirmation failed", 403, "DELETE_CONFIRMATION_FAILED"),
    );
    renderCard(<DeleteAccountCard />);

    await openDialogWithPassword("wrong");

    expect(await screen.findByText(/incorrect password/i)).toBeDefined();
    // Still on the danger card, not the terminal panel — nothing was erased.
    expect(screen.queryByText(/your account has been deleted/i)).toBeNull();
  });
});
