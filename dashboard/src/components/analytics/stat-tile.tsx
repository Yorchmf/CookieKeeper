import { ArrowDownRightIcon, ArrowUpRightIcon, MinusIcon, TriangleAlertIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * A period-over-period change badge on a tile (e.g. "+5 pts" against the prior window). Colour stays
 * neutral on purpose — for a consent product neither more nor fewer opt-ins is inherently "good", so only
 * the arrow and sign carry direction. [label] is the visible figure; [srLabel] is the full spoken sentence.
 */
export type StatTileDelta = {
  direction: "up" | "down" | "flat";
  label: string;
  srLabel: string;
};

const DELTA_ICON = {
  up: ArrowUpRightIcon,
  down: ArrowDownRightIcon,
  flat: MinusIcon,
} as const;

type StatTileProps = {
  label: string;
  value: ReactNode;
  /** Optional sub-line under the value (e.g. a share or timestamp). */
  hint?: ReactNode;
  /** Optional period-over-period change badge shown under the value; omit when there is no baseline. */
  delta?: StatTileDelta;
  /** Draw the value in the destructive colour to flag an at-risk metric (insecure cookies, trackers). */
  tone?: "default" | "warning";
  /**
   * Screen-reader-only word announced after the value when `tone` is "warning" (e.g. "Warning") — the
   * destructive colour alone doesn't reach screen readers, so the icon + this text carry the same signal.
   * Required whenever `tone="warning"` is used; ignored otherwise.
   */
  warningLabel?: string;
  className?: string;
};

/**
 * A single headline figure. Deliberately typographic — a large tabular number over a small muted label —
 * so a row of tiles reads as a scannable hierarchy rather than uniform cards. `tone="warning"` recolours
 * the number and adds an icon + sr-only label to surface a compliance risk without a heavy alert box.
 */
export function StatTile({
  label,
  value,
  hint,
  delta,
  tone = "default",
  warningLabel,
  className,
}: StatTileProps) {
  const DeltaIcon = delta ? DELTA_ICON[delta.direction] : null;
  return (
    <div
      className={cn(
        "flex flex-col gap-1 rounded-xl border border-border bg-card p-4",
        className,
      )}
    >
      <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </span>
      <span
        className={cn(
          "flex items-center gap-1.5 text-3xl font-semibold tabular-nums tracking-tight",
          tone === "warning" && "text-destructive",
        )}
      >
        {tone === "warning" ? (
          <TriangleAlertIcon aria-hidden="true" className="size-5 shrink-0" />
        ) : null}
        {value}
        {tone === "warning" && warningLabel ? (
          <span className="sr-only"> ({warningLabel})</span>
        ) : null}
      </span>
      {delta && DeltaIcon ? (
        <span className="inline-flex w-fit items-center gap-1 rounded-full border border-border px-2 py-0.5 text-xs font-medium tabular-nums text-muted-foreground">
          <DeltaIcon aria-hidden="true" className="size-3.5 shrink-0" />
          <span aria-hidden="true">{delta.label}</span>
          <span className="sr-only">{delta.srLabel}</span>
        </span>
      ) : null}
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </div>
  );
}
