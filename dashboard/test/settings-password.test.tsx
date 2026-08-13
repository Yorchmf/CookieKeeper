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
import { PasswordCard } from "@/components/settings/password-card";
import { ApiError } from "@/lib/api";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// Mock the API client, not the hook: the real `useChangePassword` (and TanStack Query) then drive the
// mutation state the card branches on — isPending/isSuccess/error all behave for real.
const changePassword = vi.fn();
vi.mock("@/lib/api/account", () => ({
  changePassword: (current: string, next: string) =>
    changePassword(current, next),
}));

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={en}>
        <PasswordCard />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function currentField(): HTMLInputElement {
  return screen.getByLabelText(/current password/i) as HTMLInputElement;
}

function newField(): HTMLInputElement {
  return screen.getByLabelText(/^new password/i) as HTMLInputElement;
}

function confirmField(): HTMLInputElement {
  return screen.getByLabelText(/confirm new password/i) as HTMLInputElement;
}

function submitButton(): HTMLButtonElement {
  return screen.getByRole("button", {
    name: /change password/i,
  }) as HTMLButtonElement;
}

/** Fills every field and submits. Defaults produce a valid change. */
function submitChange(
  values: { current?: string; next?: string; confirm?: string } = {},
) {
  const next = values.next ?? "n3w-s3cret-password";
  fireEvent.change(currentField(), {
    target: { value: values.current ?? "s3cret-password" },
  });
  fireEvent.change(newField(), { target: { value: next } });
  fireEvent.change(confirmField(), {
    target: { value: values.confirm ?? next },
  });
  fireEvent.click(submitButton());
}

beforeEach(() => {
  changePassword.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  changePassword.mockReset();
});

describe("PasswordCard", () => {
  test("sends the current and new password, then shows the signed-out panel", async () => {
    renderCard();

    submitChange({ current: "s3cret-password", next: "n3w-s3cret-password" });

    await waitFor(() =>
      expect(changePassword).toHaveBeenCalledWith(
        "s3cret-password",
        "n3w-s3cret-password",
      ),
    );
    // The change revoked every session, so the card must become terminal, not stay usable.
    expect(await screen.findByText(/password changed/i)).toBeDefined();
    const login = screen.getByRole("link", { name: /go to sign in/i });
    expect(login.getAttribute("href")).toBe("/login");
  });

  test("a too-short new password never reaches the endpoint", async () => {
    renderCard();

    submitChange({ next: "short" });

    expect(await screen.findByText(/at least 10 characters/i)).toBeDefined();
    expect(changePassword).not.toHaveBeenCalled();
  });

  test("a mismatched confirmation never reaches the endpoint", async () => {
    renderCard();

    submitChange({ next: "n3w-s3cret-password", confirm: "different-pass" });

    expect(await screen.findByText(/do not match/i)).toBeDefined();
    expect(changePassword).not.toHaveBeenCalled();
  });

  test("an empty current password never reaches the endpoint", async () => {
    renderCard();

    submitChange({ current: "" });

    expect(await screen.findByText(/enter your password/i)).toBeDefined();
    expect(changePassword).not.toHaveBeenCalled();
  });

  test("a wrong current password keeps the form and explains why", async () => {
    changePassword.mockRejectedValue(
      new ApiError(
        "Password confirmation failed",
        403,
        "CURRENT_PASSWORD_INCORRECT",
      ),
    );
    renderCard();

    submitChange();

    expect(await screen.findByText(/incorrect password/i)).toBeDefined();
    // Still on the form, not the terminal panel — nothing changed.
    expect(screen.queryByText(/password changed/i)).toBeNull();
    expect(submitButton()).toBeDefined();
  });

  test("reusing the current password surfaces the backend rejection", async () => {
    changePassword.mockRejectedValue(
      new ApiError(
        "The new password must be different from your current password",
        400,
        "NEW_PASSWORD_SAME_AS_CURRENT",
      ),
    );
    renderCard();

    submitChange();

    expect(await screen.findByText(/different from your current one/i))
      .toBeDefined();
    expect(screen.queryByText(/password changed/i)).toBeNull();
  });
});
