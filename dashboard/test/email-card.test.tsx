import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { EmailCard } from "@/components/settings/email-card";
import { ApiError } from "@/lib/api";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// Mock the API client, not the hook: the real `useRequestEmailChange` (and TanStack Query) then drive the
// mutation state the card branches on — isPending/data/error all behave for real.
const requestEmailChange = vi.fn();
vi.mock("@/lib/api/account", () => ({
  requestEmailChange: (newEmail: string, currentPassword: string) =>
    requestEmailChange(newEmail, currentPassword),
}));

// The card reads the current + pending address from `me`. `useRequestEmailChange` also imports
// ME_QUERY_KEY from here, so the mock must re-export it alongside `useMe`.
let mePendingEmail: string | null = null;
vi.mock("@/hooks/use-auth", () => ({
  ME_QUERY_KEY: ["me"],
  useMe: () => ({
    data: {
      id: "u1",
      email: "current@example.com",
      name: null,
      locale: "en",
      verifiedAt: null,
      pendingEmail: mePendingEmail,
    },
  }),
}));

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={en}>
        <EmailCard />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function newEmailField(): HTMLInputElement {
  return screen.getByLabelText(/new email address/i) as HTMLInputElement;
}

function currentPasswordField(): HTMLInputElement {
  return screen.getByLabelText(/current password/i) as HTMLInputElement;
}

function submitButton(): HTMLButtonElement {
  return screen.getByRole("button", {
    name: /send confirmation link/i,
  }) as HTMLButtonElement;
}

/** Fills both fields and submits. Defaults produce a valid request. */
function submitChange(
  values: { newEmail?: string; currentPassword?: string } = {},
) {
  fireEvent.change(newEmailField(), {
    target: { value: values.newEmail ?? "new@example.com" },
  });
  fireEvent.change(currentPasswordField(), {
    target: { value: values.currentPassword ?? "s3cret-password" },
  });
  fireEvent.click(submitButton());
}

beforeEach(() => {
  mePendingEmail = null;
  requestEmailChange.mockResolvedValue({
    id: "u1",
    email: "current@example.com",
    name: null,
    locale: "en",
    verifiedAt: null,
    pendingEmail: "new@example.com",
  });
});

afterEach(() => {
  cleanup();
  requestEmailChange.mockReset();
});

describe("EmailCard", () => {
  test("shows the current sign-in address", () => {
    renderCard();

    expect(screen.getByText("current@example.com")).toBeDefined();
  });

  test("parks the new address and shows the pending banner", async () => {
    renderCard();

    submitChange({
      newEmail: "new@example.com",
      currentPassword: "s3cret-password",
    });

    await waitFor(() =>
      expect(requestEmailChange).toHaveBeenCalledWith(
        "new@example.com",
        "s3cret-password",
      ),
    );
    // The login email is unchanged; a pending banner confirms the parked address.
    const banner = await screen.findByRole("status");
    expect(banner.textContent).toMatch(/new@example\.com/);
    expect(banner.textContent).toMatch(/waiting for confirmation/i);
  });

  test("renders the pending banner when `me` already carries one", () => {
    mePendingEmail = "already-pending@example.com";
    renderCard();

    expect(screen.getByRole("status").textContent).toMatch(
      /already-pending@example\.com/,
    );
  });

  test("an invalid email never reaches the endpoint", async () => {
    renderCard();

    submitChange({ newEmail: "not-an-email" });

    expect(await screen.findByText(/valid email address/i)).toBeDefined();
    expect(requestEmailChange).not.toHaveBeenCalled();
  });

  test("an empty current password never reaches the endpoint", async () => {
    renderCard();

    submitChange({ currentPassword: "" });

    expect(await screen.findByText(/enter your password/i)).toBeDefined();
    expect(requestEmailChange).not.toHaveBeenCalled();
  });

  test("a wrong current password explains why, email-specific", async () => {
    requestEmailChange.mockRejectedValue(
      new ApiError(
        "Password confirmation failed",
        403,
        "CURRENT_PASSWORD_INCORRECT",
      ),
    );
    renderCard();

    submitChange();

    expect(
      await screen.findByText(/your email address has not been changed/i),
    ).toBeDefined();
  });

  test("an address already in use surfaces the backend rejection", async () => {
    requestEmailChange.mockRejectedValue(
      new ApiError("Email already in use", 409, "EMAIL_IN_USE"),
    );
    renderCard();

    submitChange();

    expect(
      await screen.findByText(/already in use by another account/i),
    ).toBeDefined();
  });

  test("submitting the current address surfaces the same-as-current rejection", async () => {
    requestEmailChange.mockRejectedValue(
      new ApiError(
        "New email is the same as the current one",
        400,
        "NEW_EMAIL_SAME_AS_CURRENT",
      ),
    );
    renderCard();

    submitChange({ newEmail: "current@example.com" });

    expect(
      await screen.findByText(/that is already your email address/i),
    ).toBeDefined();
  });
});
