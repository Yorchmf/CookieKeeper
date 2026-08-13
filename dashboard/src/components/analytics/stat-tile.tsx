import { TriangleAlertIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

type StatTileProps = {
  label: string;
  value: ReactNode;
  /** Optional sub-line under the value (e.g. a share or timestamp). */
  hint?: ReactNode;
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
  tone = "default",
  warningLabel,
  className,
}: StatTileProps) {
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
      {hint ? <span className="text-xs text-muted-foreground">{hint}</span> : null}
    </div>
  );
}
