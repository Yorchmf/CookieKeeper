import { useTranslations } from "next-intl";

import type { StatTileDelta } from "@/components/analytics/stat-tile";
import type { Delta, DeltaDirection } from "@/lib/analytics/delta";

/** The two ways a delta reads: a percentage-point change (accept-share) or a relative change (event volume). */
export type DeltaUnit = "points" | "percent";

const SIGN: Record<DeltaDirection, string> = { up: "+", down: "−", flat: "" };
// Union-keyed lookups (not ternaries) so a new DeltaUnit/DeltaDirection member is a compile error here rather
// than silently falling through to the "percent"/"down" wording.
const LABEL_KEY: Record<DeltaUnit, "points" | "percent"> = { points: "points", percent: "percent" };
const SR_KEY: Record<DeltaUnit, "srPoints" | "srPercent"> = { points: "srPoints", percent: "srPercent" };
const DIR_KEY: Record<Exclude<DeltaDirection, "flat">, "dir.up" | "dir.down"> = {
  up: "dir.up",
  down: "dir.down",
};

/**
 * Formats period-over-period {@link Delta}s into stat-tile badges, sharing one presentation between the
 * per-site and cross-site analytics views so the two never drift. Returns a formatter closed over the
 * window length [days] (for the "versus the previous N days" phrasing); call it per metric.
 *
 * `unit` picks the wording: "points" is a percentage-point change (accept-share), "percent" a relative
 * change (event volume). A null delta yields `undefined` — no comparable baseline, so no badge rather than
 * a misleading zero. The badge colour is deliberately neutral (see {@link StatTileDelta}).
 */
export function useConsentDeltas(days: number): (delta: Delta | null, unit: DeltaUnit) => StatTileDelta | undefined {
  const t = useTranslations("analytics.delta");

  return (delta, unit) => {
    if (!delta) return undefined;

    const value = `${SIGN[delta.direction]}${delta.magnitude}`;
    const label = t(LABEL_KEY[unit], { value });

    if (delta.direction === "flat") {
      return { direction: "flat", label, srLabel: t("srFlat", { days }) };
    }

    const srLabel = t(SR_KEY[unit], {
      direction: t(DIR_KEY[delta.direction]),
      value: delta.magnitude,
      days,
    });
    return { direction: delta.direction, label, srLabel };
  };
}
