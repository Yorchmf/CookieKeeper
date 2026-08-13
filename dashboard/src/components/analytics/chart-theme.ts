/**
 * Chart colour tokens for the analytics surface, expressed as CSS custom-property references so every
 * series follows the app's light/dark themes automatically (the SVG `fill`/`stroke` resolve the same
 * `--brand`, `--destructive`, `--chart-*` tokens the rest of the UI uses). Kept in one place so the
 * trend chart, category bars, and cookie donut stay visually consistent rather than each inventing colours.
 *
 * Semantics, not decoration (design rule): accept = the teal "consent = go" brand, reject = destructive,
 * custom = neutral muted. The cookie taxonomy rides the deliberately monochrome `--chart-*` ramp.
 */

/** Consent-action series colours (accept / reject / custom), used by the trend chart and legends. */
export const ACTION_COLORS = {
  acceptAll: "var(--brand)",
  rejectAll: "var(--destructive)",
  custom: "var(--muted-foreground)",
} as const;

/** The single accent used for opt-in rate bars. */
export const RATE_COLOR = "var(--brand)";

/** Monochrome ramp for the cookie-category donut, cycled by slice index. */
export const CATEGORY_RAMP = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
] as const;

/** Grid, axis, and tooltip surface colours shared by every chart. */
export const CHART_SURFACE = {
  grid: "var(--border)",
  axis: "var(--muted-foreground)",
  tooltipBg: "var(--popover)",
  tooltipBorder: "var(--border)",
  tooltipText: "var(--popover-foreground)",
} as const;

/** Pick a ramp colour for the nth donut slice, wrapping if there are more slices than ramp stops. */
export function categoryColor(index: number): string {
  return CATEGORY_RAMP[index % CATEGORY_RAMP.length];
}
