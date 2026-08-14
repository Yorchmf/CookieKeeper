import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ScanHistory } from "@/components/scans/scan-history";
import type { ScanSchedule } from "@/lib/api/scans";
import en from "../messages/en.json";

// The re-scan button is an unrelated entitlement-gated widget; stub it so this test isolates the
// schedule line the history card renders.
vi.mock("@/components/scans/rescan-button", () => ({
  RescanButton: () => null,
}));

// `@/i18n/navigation` resolves `next/navigation` at module load, which happy-dom can't provide.
vi.mock("@/i18n/navigation", () => ({
  Link: ({ href, children }: { href: string; children: ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

const useScans = vi.fn();
const useScanSchedule = vi.fn();
vi.mock("@/hooks/use-scans", () => ({
  useScans: () => useScans(),
  useScanSchedule: (siteId: string) => useScanSchedule(siteId),
}));

function renderHistory() {
  return render(
    <NextIntlClientProvider locale="en" timeZone="UTC" messages={en}>
      <ScanHistory siteId="site-1" />
    </NextIntlClientProvider>,
  );
}

/**
 * `dataUpdatedAt` is the query's fetch timestamp — the card compares the due date against it instead
 * of the wall clock, so the tests pin it to a fixed "now" and stay deterministic.
 */
const FETCHED_AT = Date.parse("2026-08-14T12:00:00Z");

function withSchedule(schedule: ScanSchedule | undefined) {
  useScanSchedule.mockReturnValue({
    data: schedule,
    dataUpdatedAt: schedule ? FETCHED_AT : 0,
  });
}

beforeEach(() => {
  useScans.mockReset();
  useScanSchedule.mockReset();
  useScans.mockReturnValue({
    isPending: false,
    isError: false,
    data: { scans: [], total: 0 },
  });
  withSchedule(undefined);
});

afterEach(cleanup);

describe("ScanHistory schedule note", () => {
  test("names the date of the next scheduled scan alongside the plan cadence", () => {
    withSchedule({
      scheduled: true,
      frequency: "weekly",
      nextScanAt: "2026-09-01T12:00:00Z",
      reason: null,
    });
    renderHistory();

    expect(screen.getByText(/once a week/i)).toBeTruthy();
    expect(screen.getByText(/September 1, 2026/)).toBeTruthy();
  });

  test("reads an overdue date as due now rather than printing a date in the past", () => {
    // The job runs nightly, so a site whose due instant has passed is waiting, not late by a date.
    withSchedule({
      scheduled: true,
      frequency: "monthly",
      nextScanAt: "2026-08-01T00:00:00Z",
      reason: null,
    });
    renderHistory();

    expect(
      screen.getByText(/due and runs in the next nightly window/i),
    ).toBeTruthy();
    expect(screen.queryByText(/August 1, 2026/)).toBeNull();
  });

  test("treats a never-scanned site as due now, since it has no date to promise", () => {
    withSchedule({
      scheduled: true,
      frequency: "monthly",
      nextScanAt: null,
      reason: null,
    });
    renderHistory();

    expect(
      screen.getByText(/due and runs in the next nightly window/i),
    ).toBeTruthy();
  });

  test("still answers when the cadence is a token this build has no wording for", () => {
    // `frequency` is an open backend string typed as a closed union: an unmapped value drops the
    // cadence sentence rather than rendering a raw key, and the date still gets stated.
    withSchedule({
      scheduled: true,
      frequency: "fortnightly" as never,
      nextScanAt: "2026-09-01T12:00:00Z",
      reason: null,
    });
    renderHistory();

    expect(screen.getByText(/September 1, 2026/)).toBeTruthy();
    expect(screen.queryByText(/fortnightly/i)).toBeNull();
  });

  test.each([
    ["archived" as const, en.scans.history.paused.archived],
    ["lapsed" as const, en.scans.history.paused.lapsed],
    ["trial_ends_first" as const, en.scans.history.paused.trial_ends_first],
  ])("explains why re-scans are paused: %s", (reason, message) => {
    // Each cause reads differently to the customer — an archived site is their own doing, a lapsed
    // trial is a billing prompt — so the note names the cause instead of a cadence nothing honours.
    withSchedule({
      scheduled: false,
      frequency: null,
      nextScanAt: null,
      reason,
    });
    renderHistory();

    expect(screen.getByText(message)).toBeTruthy();
    expect(screen.queryByText(/once a month/i)).toBeNull();
    expect(screen.queryByText(/once a week/i)).toBeNull();
  });

  test("stays silent on an unscheduled reason this build has no wording for", () => {
    withSchedule({
      scheduled: false,
      frequency: null,
      nextScanAt: null,
      reason: "suspended" as never,
    });
    renderHistory();

    expect(screen.queryByText(/suspended/i)).toBeNull();
    expect(screen.queryByText(/paused/i)).toBeNull();
    // The rest of the card is unaffected.
    expect(screen.getByText(en.scans.history.title)).toBeTruthy();
  });

  test("promises nothing while the schedule is still loading", () => {
    renderHistory();

    expect(screen.queryByText(/next automatic scan/i)).toBeNull();
    expect(screen.queryByText(en.scans.history.paused.lapsed)).toBeNull();
    // The rest of the card still renders.
    expect(screen.getByText(en.scans.history.title)).toBeTruthy();
  });
});
