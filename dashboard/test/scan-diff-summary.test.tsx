import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, describe, expect, test } from "vitest";
import { ScanDiffSummary } from "@/components/scans/scan-diff-summary";
import type { ScanDiff } from "@/lib/api/scans";
import en from "../messages/en.json";

function renderDiff(diff: ScanDiff) {
  return render(
    <NextIntlClientProvider locale="en" messages={en}>
      <ScanDiffSummary diff={diff} />
    </NextIntlClientProvider>,
  );
}

const baseDiff: ScanDiff = {
  hasPrevious: true,
  previousScanId: "scan-0",
  previousScanAt: "2026-08-01T00:00:00Z",
  newCookieCount: 0,
  removedCookieCount: 0,
  addedCookieNames: [],
  removedCookieNames: [],
  trackerCountDelta: 0,
};

afterEach(cleanup);

describe("ScanDiffSummary", () => {
  test("renders nothing when there is no previous scan to compare against", () => {
    const { container } = renderDiff({
      ...baseDiff,
      hasPrevious: false,
      previousScanId: null,
      previousScanAt: null,
      trackerCountDelta: null,
    });

    expect(container).toBeTruthy();
    expect(container.firstChild).toBeNull();
  });

  test("renders nothing when a previous scan is claimed but carries no date", () => {
    // Defensive: hasPrevious and previousScanAt always travel together from the backend, but the type
    // allows them to diverge — we bail rather than print a dangling "…since your last scan on ".
    const { container } = renderDiff({
      ...baseDiff,
      hasPrevious: true,
      previousScanAt: null,
      newCookieCount: 3,
      addedCookieNames: ["_ga", "_fbp", "sid"],
    });

    expect(container.firstChild).toBeNull();
  });

  test("headlines the new-cookie count and lists added and removed names", () => {
    renderDiff({
      ...baseDiff,
      newCookieCount: 2,
      removedCookieCount: 1,
      addedCookieNames: ["_fbp", "_ga"],
      removedCookieNames: ["_hjid"],
      trackerCountDelta: 1,
    });

    // "2 new cookies since your last scan on ..." — the primary signal.
    expect(screen.getByText(/2 new cookies since your last scan/i)).toBeTruthy();
    expect(screen.getByText("_fbp")).toBeTruthy();
    expect(screen.getByText("_ga")).toBeTruthy();
    expect(screen.getByText("_hjid")).toBeTruthy();
    expect(screen.getByText(en.scans.diff.addedLabel)).toBeTruthy();
    expect(screen.getByText(en.scans.diff.removedLabel)).toBeTruthy();
  });

  test("states when nothing new was found while a previous scan exists", () => {
    renderDiff(baseDiff);

    expect(
      screen.getByText(/No new cookies since your last scan/i),
    ).toBeTruthy();
    // No added/removed sections when both lists are empty.
    expect(screen.queryByText(en.scans.diff.addedLabel)).toBeNull();
    expect(screen.queryByText(en.scans.diff.removedLabel)).toBeNull();
  });

  test("shows a rising marketing-tracker delta", () => {
    renderDiff({ ...baseDiff, trackerCountDelta: 3 });

    expect(screen.getByText(/3 more marketing trackers/i)).toBeTruthy();
  });

  test("shows a falling marketing-tracker delta as a positive count", () => {
    renderDiff({ ...baseDiff, trackerCountDelta: -2 });

    expect(screen.getByText(/2 fewer marketing trackers/i)).toBeTruthy();
  });
});
