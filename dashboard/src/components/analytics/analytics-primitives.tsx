import type { ReactNode } from "react";

/** Small inline legend swatch for the trend chart's three action series. */
export function LegendSwatch({ color, label }: { color: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ backgroundColor: color }} />
      {label}
    </span>
  );
}

/** Consistent dashed empty-state note used inside cards with no data yet. */
export function EmptyNote({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
