"use client";

import { useState, type KeyboardEvent } from "react";
import { useTranslations } from "next-intl";
import {
  CookieTable,
  orderedCategories,
} from "@/components/scans/cookie-table";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { PublicScanReport, PublicScanCookie } from "@/lib/api/public-scan";
import { isThirdPartyCookie } from "@/lib/public-scan-view";
import { cn } from "@/lib/utils";

type CookieScope = "all" | "thirdParty";

/**
 * The unlocked detailed report: the same per-category cookie tables the owned-scan results view
 * renders, grouped by canonical category (localized from the `scans` namespace) with a needs-review
 * section for cookies the signature DB did not recognize. A scope toggle narrows every table to the
 * third-party cookies (the ones a visitor most often didn't expect) without a re-fetch.
 */
export function ScanReport({ report }: { report: PublicScanReport }) {
  const t = useTranslations("marketing.scan");
  const tScans = useTranslations("scans");
  const [scope, setScope] = useState<CookieScope>("all");

  const keep = (cookie: PublicScanCookie): boolean =>
    scope === "all" || isThirdPartyCookie(cookie.domain, report.domain);

  const byCategory = report.cookiesByCategory ?? {};
  const allNeedsReview = report.needsReview ?? [];
  const needsReview = allNeedsReview.filter(keep);
  // Filter every category once (reused for the render below); drop the empties
  // so a category with no cookies in the current scope doesn't show a bare card.
  const scopedCategories = orderedCategories(Object.keys(byCategory))
    .map((category) => ({
      category,
      cookies: (byCategory[category] ?? []).filter(keep),
    }))
    .filter((entry) => entry.cookies.length > 0);

  const hasAnyCookies =
    Object.values(byCategory).some((list) => list.length > 0) ||
    allNeedsReview.length > 0;
  const hasScopedCookies =
    scopedCategories.length > 0 || needsReview.length > 0;

  const scopeOptions = ["all", "thirdParty"] as const;

  // Roving-tabindex arrow navigation for the segmented single-select (radiogroup APG pattern).
  const handleScopeKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const arrowKeys = ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"];
    if (!arrowKeys.includes(event.key)) return;
    event.preventDefault();
    const delta =
      event.key === "ArrowRight" || event.key === "ArrowDown" ? 1 : -1;
    const nextIndex =
      (scopeOptions.indexOf(scope) + delta + scopeOptions.length) %
      scopeOptions.length;
    setScope(scopeOptions[nextIndex]);
    const radios =
      event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]');
    radios[nextIndex]?.focus();
  };

  if (!hasAnyCookies) {
    return (
      <div className="rounded-lg border border-dashed border-border p-10 text-center">
        <p className="font-medium">{t("report.empty.title")}</p>
        <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
          {t("report.empty.description")}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div
        role="radiogroup"
        aria-label={t("report.filter.label")}
        onKeyDown={handleScopeKeyDown}
        className="flex w-fit gap-1 rounded-lg border border-border p-1"
      >
        {scopeOptions.map((option) => (
          <button
            key={option}
            type="button"
            role="radio"
            aria-checked={scope === option}
            tabIndex={scope === option ? 0 : -1}
            onClick={() => setScope(option)}
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
              "focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none",
              scope === option
                ? "bg-foreground text-background"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {t(`report.filter.${option}`)}
          </button>
        ))}
      </div>

      {/* Always mounted so the empty-scope message is announced when it appears
          (a live region populated at mount time is often not announced). */}
      <p
        role="status"
        className={cn(
          "text-center text-sm text-muted-foreground",
          hasScopedCookies
            ? "sr-only"
            : "rounded-lg border border-dashed border-border p-6",
        )}
      >
        {hasScopedCookies ? "" : t("report.filter.emptyThirdParty")}
      </p>

      {scopedCategories.map(({ category, cookies }) => {
        const label = tScans.has(`categories.${category}`)
          ? tScans(`categories.${category}`)
          : category;
        const hint = tScans.has(`categoryHints.${category}`)
          ? tScans(`categoryHints.${category}`)
          : null;
        return (
          <Card key={category}>
            <CardHeader>
              <CardTitle
                role="heading"
                aria-level={4}
                className="flex items-center gap-2"
              >
                {label}
                <Badge variant="secondary">{cookies.length}</Badge>
              </CardTitle>
              {hint && <CardDescription>{hint}</CardDescription>}
            </CardHeader>
            <CardContent>
              <CookieTable cookies={cookies} caption={label} />
            </CardContent>
          </Card>
        );
      })}

      {needsReview.length > 0 && (
        <Card className="border-amber-500/40">
          <CardHeader>
            <CardTitle
              role="heading"
              aria-level={4}
              className="flex items-center gap-2"
            >
              {tScans("results.needsReview.title")}
              <Badge variant="outline">{needsReview.length}</Badge>
            </CardTitle>
            <CardDescription>
              {tScans("results.needsReview.description")}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <CookieTable
              cookies={needsReview}
              caption={tScans("results.needsReview.title")}
            />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
