"use client";

import { useTranslations } from "next-intl";
import { ComplianceReport } from "@/components/scans/compliance-report";
import { orderedCategories } from "@/components/scans/cookie-table";
import { Badge } from "@/components/ui/badge";
import type { PublicScanVerdict } from "@/lib/api/public-scan";
import { cn } from "@/lib/utils";

/**
 * Semantic accent per consent category — a small dot that gives the breakdown a scannable colour code
 * (necessary reads calm/green, marketing reads attention/amber). Decorative, so the dot is aria-hidden.
 */
const CATEGORY_DOT: Record<string, string> = {
  necessary: "bg-emerald-500",
  preferences: "bg-sky-500",
  statistics: "bg-violet-500",
  marketing: "bg-amber-500",
};

/**
 * The free headline of a completed scan: the indicative compliance score and what pulled it down (the
 * motivating signal — "here's what's wrong and how bad"), then a dominant total-cookie numeral, a
 * colour-coded per-category breakdown, and a needs-review flag. Counts and issue codes only — the
 * cookie-level detail is the email-gated upsell rendered separately by the report.
 */
export function ScanVerdict({
  verdict,
  domain,
}: {
  verdict: PublicScanVerdict;
  domain: string;
}) {
  const t = useTranslations("marketing.scan");
  const tCats = useTranslations("scans");
  const categories = orderedCategories(Object.keys(verdict.cookiesByCategory));

  return (
    <div className="flex flex-col gap-6">
      <ComplianceReport report={verdict.compliance} />

      <div className="flex items-end gap-4">
        <span className="text-6xl leading-none font-semibold tracking-tight tabular-nums sm:text-7xl">
          {verdict.totalCookies}
        </span>
        <span className="max-w-[14ch] pb-1 text-sm text-pretty text-muted-foreground">
          {t("verdict.totalLabel", { domain })}
        </span>
      </div>

      {categories.length > 0 && (
        <ul className="grid gap-x-6 gap-y-2 sm:grid-cols-2">
          {categories.map((category) => (
            <li
              key={category}
              className="flex items-center gap-2.5 border-b border-border/60 py-1.5 last:border-0"
            >
              <span
                aria-hidden="true"
                className={cn(
                  "size-2.5 shrink-0 rounded-full",
                  CATEGORY_DOT[category] ?? "bg-muted-foreground",
                )}
              />
              <span className="text-sm font-medium">
                {tCats.has(`categories.${category}`)
                  ? tCats(`categories.${category}`)
                  : category}
              </span>
              <span className="ml-auto text-sm tabular-nums text-muted-foreground">
                {verdict.cookiesByCategory[category]}
              </span>
            </li>
          ))}
        </ul>
      )}

      {verdict.needsReviewCount > 0 && (
        <Badge variant="outline" className="w-fit border-amber-500/50">
          {t("verdict.needsReview", { count: verdict.needsReviewCount })}
        </Badge>
      )}
    </div>
  );
}
