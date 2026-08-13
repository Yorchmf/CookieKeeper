"use client";

import { useTranslations } from "next-intl";

import type { LanguageCount } from "@/lib/api/analytics";

/**
 * Visitor-language mix over the window as a compact labelled meter list (most frequent first). A full
 * chart would be overkill for a handful of language codes; proportional bars with the raw count read
 * faster and stay legible at any width. An empty `lang` collapses to a localized "unknown" label.
 */
export function LanguageSplit({ languages }: { languages: LanguageCount[] }) {
  const t = useTranslations("analytics");
  const total = languages.reduce((sum, entry) => sum + entry.count, 0);
  const max = languages.reduce((peak, entry) => Math.max(peak, entry.count), 0);

  return (
    <ul className="flex flex-col gap-3">
      {languages.map((entry, index) => {
        const label = entry.lang ? entry.lang.toUpperCase() : t("languages.unknown");
        const share = total === 0 ? 0 : Math.round((entry.count / total) * 100);
        const width = max === 0 ? 0 : Math.round((entry.count / max) * 100);
        return (
          <li key={`${entry.lang || "unknown"}-${index}`} className="flex flex-col gap-1">
            <div className="flex items-baseline justify-between text-sm">
              <span className="font-medium">{label}</span>
              <span className="tabular-nums text-muted-foreground">
                {t("languages.count", { count: entry.count, share })}
              </span>
            </div>
            {/* Decorative reinforcement of the label/share text above — hidden from AT to avoid a
                duplicate announcement (the count + share are already read from the row header). */}
            <div className="h-2 overflow-hidden rounded-full bg-muted" aria-hidden="true">
              <div className="h-full rounded-full bg-brand" style={{ width: `${width}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
