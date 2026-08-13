"use client";

import { useTranslations } from "next-intl";
import { useRef } from "react";

import type { SiteStatus } from "@/lib/api/sites";
import { cn } from "@/lib/utils";

/** The two site lifecycles a customer can browse. Active is the default view. */
export const SITE_STATUS_OPTIONS = ["active", "archived"] as const satisfies readonly SiteStatus[];

/** Narrow an untrusted URL value to a supported status, defaulting to the active view. */
export function parseSiteStatus(raw: string | null | undefined): SiteStatus {
  return (SITE_STATUS_OPTIONS as readonly string[]).includes(raw ?? "")
    ? (raw as SiteStatus)
    : "active";
}

/**
 * Segmented Active / Archived picker for the sites list. Implemented as a WAI-ARIA APG radio group: a
 * single tab stop (roving `tabIndex`, only the checked option is tabbable) with arrow / Home / End keys
 * moving and selecting. State lives in the URL (the parent writes `?status=`), keeping the chosen view
 * shareable and back-button friendly. Mirrors `analytics/RangeSelector`.
 */
export function SiteStatusFilter({
  value,
  onChange,
}: {
  value: SiteStatus;
  onChange: (status: SiteStatus) => void;
}) {
  const t = useTranslations("sites.filter");
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const selectAt = (index: number) => {
    const count = SITE_STATUS_OPTIONS.length;
    const next = ((index % count) + count) % count;
    onChange(SITE_STATUS_OPTIONS[next]);
    buttonRefs.current[next]?.focus();
  };

  const handleKeyDown = (event: React.KeyboardEvent, index: number) => {
    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        event.preventDefault();
        selectAt(index + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        event.preventDefault();
        selectAt(index - 1);
        break;
      case "Home":
        event.preventDefault();
        selectAt(0);
        break;
      case "End":
        event.preventDefault();
        selectAt(SITE_STATUS_OPTIONS.length - 1);
        break;
    }
  };

  return (
    <div
      role="radiogroup"
      aria-label={t("legend")}
      className="inline-flex rounded-lg border border-border bg-card p-0.5"
    >
      {SITE_STATUS_OPTIONS.map((status, index) => {
        const isActive = status === value;
        return (
          <button
            key={status}
            ref={(el) => {
              buttonRefs.current[index] = el;
            }}
            type="button"
            role="radio"
            aria-checked={isActive}
            tabIndex={isActive ? 0 : -1}
            onClick={() => onChange(status)}
            onKeyDown={(event) => handleKeyDown(event, index)}
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium outline-none transition-colors",
              "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1",
              isActive
                ? "bg-brand text-brand-foreground"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {t(`status.${status}`)}
          </button>
        );
      })}
    </div>
  );
}
