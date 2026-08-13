"use client";

import { useTranslations } from "next-intl";
import { useRef } from "react";

import { cn } from "@/lib/utils";

/** The trend windows offered as presets, in days. Mirrors the backend's 30-day default in the middle. */
export const RANGE_OPTIONS = [7, 30, 90] as const;
export type RangeDays = (typeof RANGE_OPTIONS)[number];

/** Narrow an untrusted URL value to a supported range, defaulting to 30 days. */
export function parseRange(raw: string | null | undefined): RangeDays {
  const parsed = Number(raw);
  return (RANGE_OPTIONS as readonly number[]).includes(parsed) ? (parsed as RangeDays) : 30;
}

/**
 * Segmented window picker (7 / 30 / 90 days). Implemented as a WAI-ARIA APG radio group: a single tab
 * stop (roving `tabIndex`, only the checked option is tabbable) with arrow / Home / End keys moving and
 * selecting, matching the `radiogroup`/`radio` roles announced to assistive tech. State lives in the URL
 * (the parent writes `?range=`), keeping the chosen window shareable and back-button friendly.
 */
export function RangeSelector({
  value,
  onChange,
}: {
  value: RangeDays;
  onChange: (days: RangeDays) => void;
}) {
  const t = useTranslations("analytics.range");
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  // Select the option at `index` (wrapping) and move focus to it — arrow keys both move and select, per
  // the radio-group pattern.
  const selectAt = (index: number) => {
    const count = RANGE_OPTIONS.length;
    const next = ((index % count) + count) % count;
    onChange(RANGE_OPTIONS[next]);
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
        selectAt(RANGE_OPTIONS.length - 1);
        break;
    }
  };

  return (
    <div
      role="radiogroup"
      aria-label={t("legend")}
      className="inline-flex rounded-lg border border-border bg-card p-0.5"
    >
      {RANGE_OPTIONS.map((days, index) => {
        const isActive = days === value;
        return (
          <button
            key={days}
            ref={(el) => {
              buttonRefs.current[index] = el;
            }}
            type="button"
            role="radio"
            aria-checked={isActive}
            tabIndex={isActive ? 0 : -1}
            onClick={() => onChange(days)}
            onKeyDown={(event) => handleKeyDown(event, index)}
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium outline-none transition-colors",
              "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1",
              isActive
                ? "bg-brand text-brand-foreground"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {t("days", { days })}
          </button>
        );
      })}
    </div>
  );
}
