import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test, vi } from "vitest";
import { NotificationsCard } from "@/components/settings/notifications-card";
import en from "../messages/en.json";

// Hoisted so the mock factory can read the same holder the tests mutate per case.
const h = vi.hoisted(() => ({
  mutate: vi.fn(),
  prefs: {
    data: undefined as { scanComplete: boolean; scanChanges: boolean } | undefined,
    isPending: false,
    isError: false,
  },
}));

vi.mock("@/hooks/use-account", () => ({
  useNotificationPreferences: () => h.prefs,
  useUpdateNotificationPreferences: () => ({ mutate: h.mutate }),
}));

function renderCard() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <NotificationsCard />
    </NextIntlClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  h.mutate.mockReset();
  h.prefs = { data: undefined, isPending: false, isError: false };
});

describe("NotificationsCard", () => {
  test("shows a toggle per email with its explanation", () => {
    h.prefs = {
      data: { scanComplete: true, scanChanges: true },
      isPending: false,
      isError: false,
    };
    renderCard();

    expect(screen.getByText(/first scan complete/i)).toBeDefined();
    expect(screen.getByText(/new or changed trackers/i)).toBeDefined();
    // Both switches on the page, reflecting the all-on state.
    const switches = screen.getAllByRole("switch");
    expect(switches.length).toBe(2);
    expect(switches.every((s) => s.getAttribute("aria-checked") === "true")).toBe(
      true,
    );
  });

  test("turning a toggle off saves the full pair with only that flag flipped", () => {
    h.prefs = {
      data: { scanComplete: true, scanChanges: true },
      isPending: false,
      isError: false,
    };
    renderCard();

    // Display order: [0] = first-scan, [1] = change alert.
    fireEvent.click(screen.getAllByRole("switch")[0]);

    // The PUT requires both flags, so the other must be preserved, not dropped.
    expect(h.mutate).toHaveBeenCalledWith({
      scanComplete: false,
      scanChanges: true,
    });
  });

  test("the two toggles are independent — flipping one preserves the other", () => {
    h.prefs = {
      data: { scanComplete: false, scanChanges: true },
      isPending: false,
      isError: false,
    };
    renderCard();

    fireEvent.click(screen.getAllByRole("switch")[1]);

    expect(h.mutate).toHaveBeenCalledWith({
      scanComplete: false,
      scanChanges: false,
    });
  });

  test("shows placeholders and no toggles while the preferences are loading", () => {
    h.prefs = { data: undefined, isPending: true, isError: false };
    renderCard();

    expect(screen.queryAllByRole("switch").length).toBe(0);
  });

  test("explains the failure instead of a broken toggle when the load errors", () => {
    h.prefs = { data: undefined, isPending: false, isError: true };
    renderCard();

    expect(screen.getByText(/couldn't load your notification preferences/i))
      .toBeDefined();
    expect(screen.queryAllByRole("switch").length).toBe(0);
  });
});
