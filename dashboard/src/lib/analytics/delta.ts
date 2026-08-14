/**
 * Period-over-period delta math for the analytics views — pure and i18n-free (formatting lives in the
 * component layer). A "delta" compares the window on display against the one immediately before it (the
 * backend's `previous` baseline). Every function returns null when there is no comparable baseline, so a
 * caller renders no badge rather than a misleading one: a brand-new account, or a prior window the backend
 * omitted because it fell below the plan retention floor (ADR-16), simply has nothing to compare against.
 */

export type DeltaDirection = "up" | "down" | "flat";

/** A resolved change: its [direction] and the absolute [magnitude] (the sign is carried by the direction). */
export interface Delta {
  direction: DeltaDirection;
  magnitude: number;
}

/** The lean consent totals a delta needs — the shape of both the current window and the `previous` baseline. */
export interface ActionTotals {
  totalEvents: number;
  byAction: { acceptAll: number; rejectAll: number; custom: number };
}

function toDelta(signed: number): Delta {
  return {
    direction: signed > 0 ? "up" : signed < 0 ? "down" : "flat",
    magnitude: Math.abs(signed),
  };
}

/** Accept-all share as a whole-percent 0–100; 0 when the window carried no events (matches the stat tile). */
export function acceptSharePct(summary: ActionTotals): number {
  return summary.totalEvents === 0
    ? 0
    : Math.round((summary.byAction.acceptAll / summary.totalEvents) * 100);
}

/**
 * Percentage-point change in accept-share between the current window and its baseline (e.g. 65% → 70% is
 * +5). Null when there is no baseline or it carried no events — a rate has no meaning over zero decisions,
 * so there is nothing to compare.
 */
export function acceptShareDelta(current: ActionTotals, previous: ActionTotals | null): Delta | null {
  if (!previous || previous.totalEvents === 0) return null;
  // Subtract the two already-rounded whole-percent shares (not the raw difference) so the badge always equals
  // the arithmetic of the two rounded percentages the tiles display — no "+1" that neither tile can account for.
  return toDelta(acceptSharePct(current) - acceptSharePct(previous));
}

/**
 * Relative percent change in event volume between the current window and its baseline (200 → 260 is +30).
 * Null when there is no baseline or it carried no events — dividing by a zero base yields no meaningful
 * percentage, so no delta is shown.
 */
export function eventsDelta(current: number, previous: ActionTotals | null): Delta | null {
  if (!previous || previous.totalEvents === 0) return null;
  return toDelta(Math.round(((current - previous.totalEvents) / previous.totalEvents) * 100));
}
