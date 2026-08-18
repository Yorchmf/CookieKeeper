import { describe, expect, test } from "vitest";

import {
  acceptShareDelta,
  acceptSharePct,
  eventsDelta,
  impressionsDelta,
  interactionRateDelta,
  interactionRatePct,
} from "@/lib/analytics/delta";

const totals = (acceptAll: number, rejectAll: number, custom: number) => ({
  totalEvents: acceptAll + rejectAll + custom,
  byAction: { acceptAll, rejectAll, custom },
});

const interaction = (totalEvents: number, impressions: number) => ({ totalEvents, impressions });

describe("acceptSharePct", () => {
  test("rounds accept-all over total to whole percent", () => {
    expect(acceptSharePct(totals(130, 40, 30))).toBe(65);
  });

  test("is 0 for an empty window instead of dividing by zero", () => {
    expect(acceptSharePct(totals(0, 0, 0))).toBe(0);
  });
});

describe("acceptShareDelta", () => {
  test("is the percentage-point difference between current and baseline share", () => {
    // 65% now vs 50% before → up 15 points.
    const delta = acceptShareDelta(totals(130, 40, 30), totals(50, 30, 20));
    expect(delta).toEqual({ direction: "up", magnitude: 15 });
  });

  test("reports a drop as a down direction with an absolute magnitude", () => {
    // 40% now vs 60% before → down 20 points.
    const delta = acceptShareDelta(totals(40, 40, 20), totals(60, 30, 10));
    expect(delta).toEqual({ direction: "down", magnitude: 20 });
  });

  test("is flat when the share is unchanged", () => {
    expect(acceptShareDelta(totals(50, 50, 0), totals(25, 25, 0))).toEqual({
      direction: "flat",
      magnitude: 0,
    });
  });

  test("is null without a baseline — no window to compare", () => {
    expect(acceptShareDelta(totals(130, 40, 30), null)).toBeNull();
  });

  test("is null when the baseline carried no decisions — a rate over zero is meaningless", () => {
    expect(acceptShareDelta(totals(130, 40, 30), totals(0, 0, 0))).toBeNull();
  });
});

describe("eventsDelta", () => {
  test("is the relative percent change in event volume", () => {
    // 200 now vs 100 before → +100%.
    expect(eventsDelta(200, totals(60, 30, 10))).toEqual({ direction: "up", magnitude: 100 });
  });

  test("reports a volume drop as a down direction", () => {
    // 60 now vs 120 before → −50%.
    expect(eventsDelta(60, totals(70, 30, 20))).toEqual({ direction: "down", magnitude: 50 });
  });

  test("is null without a baseline", () => {
    expect(eventsDelta(200, null)).toBeNull();
  });

  test("is null when the baseline carried no events — no base to divide by", () => {
    expect(eventsDelta(200, totals(0, 0, 0))).toBeNull();
  });
});

describe("interactionRatePct", () => {
  test("rounds decisions over impressions to whole percent", () => {
    // 40 decisions / 100 impressions → 40%.
    expect(interactionRatePct(interaction(40, 100))).toBe(40);
  });

  test("is 0 when no impression was recorded instead of dividing by zero", () => {
    expect(interactionRatePct(interaction(5, 0))).toBe(0);
  });

  test("is reported raw and can exceed 100% at window edges", () => {
    // More decisions than impressions (re-consent without a fresh impression) → 150%, not clamped.
    expect(interactionRatePct(interaction(150, 100))).toBe(150);
  });
});

describe("interactionRateDelta", () => {
  test("is the percentage-point difference between current and baseline rate", () => {
    // 55% now vs 40% before → up 15 points.
    const delta = interactionRateDelta(interaction(55, 100), interaction(40, 100));
    expect(delta).toEqual({ direction: "up", magnitude: 15 });
  });

  test("reports a drop as a down direction with an absolute magnitude", () => {
    // 30% now vs 50% before → down 20 points.
    const delta = interactionRateDelta(interaction(30, 100), interaction(100, 200));
    expect(delta).toEqual({ direction: "down", magnitude: 20 });
  });

  test("is null without a baseline", () => {
    expect(interactionRateDelta(interaction(40, 100), null)).toBeNull();
  });

  test("is null when the baseline recorded no impressions — a rate over zero is meaningless", () => {
    expect(interactionRateDelta(interaction(40, 100), interaction(10, 0))).toBeNull();
  });
});

describe("impressionsDelta", () => {
  test("is the relative percent change in impression volume", () => {
    // 650 now vs 500 before → +30%.
    expect(impressionsDelta(650, { impressions: 500 })).toEqual({ direction: "up", magnitude: 30 });
  });

  test("reports a volume drop as a down direction", () => {
    // 250 now vs 500 before → −50%.
    expect(impressionsDelta(250, { impressions: 500 })).toEqual({
      direction: "down",
      magnitude: 50,
    });
  });

  test("is null without a baseline", () => {
    expect(impressionsDelta(650, null)).toBeNull();
  });

  test("is null when the baseline recorded no impressions — no base to divide by", () => {
    expect(impressionsDelta(650, { impressions: 0 })).toBeNull();
  });
});
