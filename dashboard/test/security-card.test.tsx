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
import { SecurityCard } from "@/components/settings/security-card";
import { ApiError } from "@/lib/api";
import en from "../messages/en.json";

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// Mock the API client, not the hook: the real `useSignOutEverywhere` (and TanStack Query) then drive the
// mutation state the card branches on — isPending/isSuccess/error all behave for real.
const signOutEverywhere = vi.fn();
vi.mock("@/lib/api/account", () => ({
  signOutEverywhere: (currentPassword: string) =>
    signOutEverywhere(currentPassword),
}));

function renderCard() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={en}>
        <SecurityCard />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

function currentPasswordField(): HTMLInputElement {
  return screen.getByLabelText(/current password/i) as HTMLInputElement;
}

function submitButton(): HTMLButtonElement {
  return screen.getByRole("button", {
    name: /sign out all devices/i,
  }) as HTMLButtonElement;
}

/** Fills the password and submits. Default produces a valid request. */
function submit(currentPassword = "s3cret-password") {
  fireEvent.change(currentPasswordField(), {
    target: { value: currentPassword },
  });
  fireEvent.click(submitButton());
}

beforeEach(() => {
  signOutEverywhere.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  signOutEverywhere.mockReset();
});

describe("SecurityCard", () => {
  test("re-authenticates and shows the terminal signed-out panel", async () => {
    renderCard();

    submit("s3cret-password");

    await waitFor(() =>
      expect(signOutEverywhere).toHaveBeenCalledWith("s3cret-password"),
    );
    // The form is replaced by a terminal panel pointing back at sign-in.
    expect(await screen.findByText(/signed out everywhere/i)).toBeDefined();
    const login = screen.getByRole("link", { name: /go to sign in/i });
    expect(login.getAttribute("href")).toBe("/login");
    // The password field is gone — nothing can be re-submitted into a dead session.
    expect(screen.queryByLabelText(/current password/i)).toBeNull();
  });

  test("an empty password never reaches the endpoint", async () => {
    renderCard();

    submit("");

    expect(await screen.findByText(/enter your password/i)).toBeDefined();
    expect(signOutEverywhere).not.toHaveBeenCalled();
  });

  test("a wrong password explains why, without signing out", async () => {
    signOutEverywhere.mockRejectedValue(
      new ApiError(
        "Password confirmation failed",
        403,
        "CURRENT_PASSWORD_INCORRECT",
      ),
    );
    renderCard();

    submit();

    expect(
      await screen.findByText(/you have not been signed out/i),
    ).toBeDefined();
    // Still the form, not the terminal panel — the session survives a rejected attempt.
    expect(screen.getByLabelText(/current password/i)).toBeDefined();
  });

  test("a rate-limit rejection surfaces the throttle message", async () => {
    signOutEverywhere.mockRejectedValue(
      new ApiError("Too many attempts", 429, "RATE_LIMITED"),
    );
    renderCard();

    submit();

    expect(await screen.findByText(/too many attempts/i)).toBeDefined();
  });

  test("an unmapped backend code falls back to the generic message", async () => {
    signOutEverywhere.mockRejectedValue(
      new ApiError("Boom", 500, "SOMETHING_UNEXPECTED"),
    );
    renderCard();

    submit();

    expect(await screen.findByText(/something went wrong/i)).toBeDefined();
  });
});
