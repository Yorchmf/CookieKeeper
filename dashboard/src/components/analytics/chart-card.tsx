import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

type ChartCardProps = {
  title: string;
  description?: string;
  /** Rendered top-right of the header (e.g. a legend or a total). */
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
};

/**
 * Framed surface for one visualization. Owns the header (title + optional description + aside slot) and
 * the padded body so the individual charts stay pure — they receive a sized box and render into it. The
 * heading is a real `<h2>` for document outline and screen-reader navigation.
 */
export function ChartCard({ title, description, aside, children, className }: ChartCardProps) {
  return (
    <section
      className={cn(
        "flex flex-col gap-4 rounded-2xl border border-border bg-card p-5",
        className,
      )}
    >
      <header className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
          {description ? (
            <p className="text-xs text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {aside ? <div className="text-xs text-muted-foreground">{aside}</div> : null}
      </header>
      {children}
    </section>
  );
}
