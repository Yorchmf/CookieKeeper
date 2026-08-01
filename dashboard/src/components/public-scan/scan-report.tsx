"use client";

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
import type { PublicScanReport } from "@/lib/api/public-scan";

/**
 * The unlocked detailed report: the same per-category cookie tables the owned-scan results view
 * renders, grouped by canonical category (localized from the `scans` namespace) with a needs-review
 * section for cookies the signature DB did not recognize.
 */
export function ScanReport({ report }: { report: PublicScanReport }) {
  const t = useTranslations("marketing.scan");
  const tScans = useTranslations("scans");

  const byCategory = report.cookiesByCategory ?? {};
  const needsReview = report.needsReview ?? [];
  const categories = orderedCategories(Object.keys(byCategory));
  const hasCookies = categories.length > 0 || needsReview.length > 0;

  if (!hasCookies) {
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
      {categories.map((category) => {
        const cookies = byCategory[category] ?? [];
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
