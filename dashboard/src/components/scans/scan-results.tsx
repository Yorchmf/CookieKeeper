"use client";

import { useFormatter, useTranslations } from "next-intl";
import { BlockingVerification } from "@/components/scans/blocking-verification";
import { ComplianceReport } from "@/components/scans/compliance-report";
import {
  CookieTable,
  orderedCategories,
} from "@/components/scans/cookie-table";
import { ScanDiffSummary } from "@/components/scans/scan-diff-summary";
import { ScanStatusBadge } from "@/components/scans/scan-status-badge";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useScan } from "@/hooks/use-scans";
import { Link } from "@/i18n/navigation";

/** Focus ring shared by the standalone back links so keyboard focus is always visible. */
const BACK_LINK_CLASS =
  "inline-block rounded text-sm text-muted-foreground underline-offset-4 hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 outline-none";

export function ScanResults({
  siteId,
  scanId,
}: {
  siteId: string;
  scanId: string;
}) {
  const t = useTranslations("scans");
  const format = useFormatter();
  const scan = useScan(siteId, scanId);

  if (scan.isPending) {
    return (
      <main className="flex-1 p-6" aria-busy="true">
        <div className="flex max-w-4xl flex-col gap-4" aria-hidden="true">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      </main>
    );
  }

  if (scan.isError) {
    return (
      <main className="flex-1 p-6">
        <div className="flex max-w-4xl flex-col gap-4">
          <Link href={`/sites/${siteId}`} className={BACK_LINK_CLASS}>
            {t("results.backToSite")}
          </Link>
          <p role="alert" className="text-sm text-destructive">
            {t("results.loadError")}
          </p>
        </div>
      </main>
    );
  }

  const data = scan.data;
  // Guard against a null-normalized payload so we never call Object.keys on undefined.
  const byCategory = data.cookiesByCategory ?? {};
  const needsReview = data.needsReview ?? [];
  const categories = orderedCategories(Object.keys(byCategory));
  const hasCookies = categories.length > 0 || needsReview.length > 0;

  return (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="scan-results-heading"
        className="flex max-w-4xl flex-col gap-6"
      >
        <div>
          <Link href={`/sites/${siteId}`} className={BACK_LINK_CLASS}>
            {t("results.backToSite")}
          </Link>
        </div>

        <header className="flex flex-wrap items-center gap-3">
          <h1
            id="scan-results-heading"
            className="text-2xl font-semibold tracking-tight"
          >
            {t("results.title")}
          </h1>
          <ScanStatusBadge status={data.status} />
          <span className="text-sm text-muted-foreground">
            {format.dateTime(new Date(data.finishedAt ?? data.createdAt), {
              year: "numeric",
              month: "short",
              day: "numeric",
              hour: "2-digit",
              minute: "2-digit",
            })}
          </span>
          {data.pagesCrawled != null && (
            <span className="text-sm text-muted-foreground">
              {t("history.pagesCrawled", { count: data.pagesCrawled })}
            </span>
          )}
        </header>

        {data.diff && <ScanDiffSummary diff={data.diff} />}

        {data.compliance && <ComplianceReport report={data.compliance} />}

        {data.blocking && (
          <BlockingVerification verification={data.blocking} siteId={siteId} />
        )}

        {!hasCookies ? (
          <div className="rounded-lg border border-dashed border-border p-10 text-center">
            <p className="font-medium">{t("results.empty.title")}</p>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">
              {t("results.empty.description")}
            </p>
          </div>
        ) : (
          <>
            {categories.map((category) => {
              const cookies = byCategory[category] ?? [];
              const label = t.has(`categories.${category}`)
                ? t(`categories.${category}`)
                : category;
              const hint = t.has(`categoryHints.${category}`)
                ? t(`categoryHints.${category}`)
                : null;
              return (
                <Card key={category}>
                  <CardHeader>
                    <CardTitle
                      role="heading"
                      aria-level={2}
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
                    aria-level={2}
                    className="flex items-center gap-2"
                  >
                    {t("results.needsReview.title")}
                    <Badge variant="outline">{needsReview.length}</Badge>
                  </CardTitle>
                  <CardDescription>
                    {t("results.needsReview.description")}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <CookieTable
                    cookies={needsReview}
                    caption={t("results.needsReview.title")}
                  />
                </CardContent>
              </Card>
            )}
          </>
        )}
      </section>
    </main>
  );
}
