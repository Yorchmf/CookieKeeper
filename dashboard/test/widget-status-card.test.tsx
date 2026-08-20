import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";

import { WidgetStatusCard } from "@/components/sites/widget-status-card";
import type { WidgetStatus } from "@/lib/api/widget-status";
import en from "../messages/en.json";

// Stub the query hook so the card renders without a QueryClientProvider; each test supplies the state
// under test and the spy proves "Check again" actually re-asks the server.
const useWidgetStatus = vi.fn();
vi.mock("@/hooks/use-widget-status", () => ({
  useWidgetStatus: (siteId: string) => useWidgetStatus(siteId),
}));

const copy = en.sites.detail.widgetStatus;

function settled(data: WidgetStatus | null, overrides: Record<string, unknown> = {}) {
  return {
    data,
    isPending: false,
    isError: data === null,
    isFetching: false,
    refetch: vi.fn().mockResolvedValue({ data }),
    ...overrides,
  };
}

const ACTIVE: WidgetStatus = {
  state: "active",
  lastSeenDay: "2026-08-18",
  impressionsToday: 4,
  impressionsInWindow: 9,
  windowDays: 7,
};

function renderCard() {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <WidgetStatusCard siteId="site-1" />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  useWidgetStatus.mockReset();
});

afterEach(cleanup);

describe("WidgetStatusCard", () => {
  test("a site that has never been seen gets the install instruction, not a failure verdict", () => {
    useWidgetStatus.mockReturnValue(
      settled({
        state: "never_seen",
        lastSeenDay: null,
        impressionsToday: 0,
        impressionsInWindow: 0,
        windowDays: 7,
      }),
    );
    renderCard();

    expect(screen.getByText(copy.badge.never_seen)).toBeTruthy();
    expect(screen.getByText(copy.body.never_seen)).toBeTruthy();
    // The caveat that makes every count on this card honest is always present.
    expect(screen.getByText(copy.note)).toBeTruthy();
  });

  test("a live widget reports the last day it was seen and both counts", () => {
    useWidgetStatus.mockReturnValue(settled(ACTIVE));
    renderCard();

    expect(screen.getByText(copy.badge.active)).toBeTruthy();
    const body = screen.getByText(/Last seen on 2026-08-18/);
    // The window count and today's share of it, from the same payload that decided the verdict.
    expect(body.textContent).toContain("9 banner views in the last 7 days");
    expect(body.textContent).toContain("4 of them today");
  });

  test("quiet is reported as ambiguous — both the benign and the broken reading", () => {
    useWidgetStatus.mockReturnValue(
      settled({
        state: "idle",
        lastSeenDay: "2026-07-30",
        impressionsToday: 0,
        impressionsInWindow: 0,
        windowDays: 7,
      }),
    );
    renderCard();

    expect(screen.getByText(copy.badge.idle)).toBeTruthy();
    const body = screen.getByText(/Last seen on 2026-07-30/).textContent ?? "";
    // Only undecided visitors fire the beacon, so silence must never be sold as "your widget is broken".
    expect(body).toContain("That is normal if your regular visitors have already made their choice");
    expect(body).toContain("check that the snippet is still on your pages");
  });

  test("the state is carried by text, not colour alone", () => {
    useWidgetStatus.mockReturnValue(settled(ACTIVE));
    const { container } = renderCard();

    // An icon accompanies the badge but is hidden from AT — the badge's own text is the signal.
    expect(container.querySelector('[data-slot="badge"] svg')?.getAttribute("aria-hidden")).toBe("true");
    expect(screen.getByText(copy.badge.active)).toBeTruthy();
  });

  test("Check again refetches and announces the settled verdict in a live region", async () => {
    const refetch = vi.fn().mockResolvedValue({ data: ACTIVE });
    useWidgetStatus.mockReturnValue(settled(ACTIVE, { refetch }));
    renderCard();

    // The live region exists before any interaction — an announcement into a node that only appears
    // with the message is routinely missed by screen readers.
    const live = screen.getByRole("status");
    expect(live.getAttribute("aria-live")).toBe("polite");
    expect(live.textContent).toBe("");

    fireEvent.click(screen.getByRole("button", { name: copy.checkAgain }));

    expect(refetch).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(live.textContent).toBe(copy.badge.active));
  });

  test("a failed load says so and still offers the retry", () => {
    useWidgetStatus.mockReturnValue(settled(null));
    renderCard();

    expect(screen.getByText(copy.loadError)).toBeTruthy();
    // No badge to state without data, but the customer is not stranded.
    expect(screen.queryByText(copy.badge.active)).toBeNull();
    expect(screen.getByRole("button", { name: copy.checkAgain })).toBeTruthy();
  });

  test("a re-check in flight is announced as busy rather than silently ignored", () => {
    useWidgetStatus.mockReturnValue(settled(ACTIVE, { isFetching: true }));
    renderCard();

    const button = screen.getByRole("button", { name: copy.checking });
    expect(button.getAttribute("aria-busy")).toBe("true");
    expect(button.getAttribute("aria-disabled")).toBe("true");
  });
});
