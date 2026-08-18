"use client";

import { useFormatter, useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import { useCallback, useMemo, useState } from "react";

import { EmptyNote, LegendSwatch } from "@/components/analytics/analytics-primitives";
import { CategoryOptInChart } from "@/components/analytics/category-opt-in-chart";
import { ACTION_COLORS } from "@/components/analytics/chart-theme";
import { ChartCard } from "@/components/analytics/chart-card";
import { ConsentTrendChart } from "@/components/analytics/consent-trend-chart";
import { CookieInventory } from "@/components/analytics/cookie-inventory";
import { DownloadEvidencePackButton } from "@/components/analytics/download-evidence-pack-button";
import { ExportAnalyticsButton } from "@/components/analytics/export-analytics-button";
import { LanguageSplit } from "@/components/analytics/language-split";
import { parseRange, type RangeDays, RangeSelector } from "@/components/analytics/range-selector";
import { StatTile } from "@/components/analytics/stat-tile";
import { useConsentDeltas } from "@/components/analytics/use-consent-deltas";
import { Skeleton } from "@/components/ui/skeleton";
import { useSiteAnalytics } from "@/hooks/use-analytics";
import { usePathname, useRouter } from "@/i18n/navigation";
import {
  acceptShareDelta,
  acceptSharePct,
  eventsDelta,
  impressionsDelta,
  interactionRateDelta,
  interactionRatePct,
} from "@/lib/analytics/delta";
import type { AnalyticsFilter } from "@/lib/api/analytics";
import { cn } from "@/lib/utils";

const MS_PER_DAY = 86_400_000;

/** Loading placeholder mirroring the dashboard layout. Exported for the route Suspense fallback. */
export function AnalyticsSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn("flex flex-col gap-6", className)} aria-hidden="true">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {Array.from({ length: 6 }).map((_, index) => (
          <Skeleton key={index} className="h-24 w-full rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-72 w-full rounded-2xl" />
      <div className="grid gap-6 lg:grid-cols-2">
        <Skeleton className="h-64 w-full rounded-2xl" />
        <Skeleton className="h-64 w-full rounded-2xl" />
      </div>
    </div>
  );
}

/**
 * Site analytics dashboard. The window preset lives in the URL (`?range=`, shareable) and resolves to a
 * concrete `from` instant for the API; `to` is left open so the backend anchors it to now. Every figure
 * comes from one aggregated read — consent trend/mix, per-category opt-in, language split, the latest
 * scan's cookie inventory, and the current policy version.
 */
export function Analytics({ siteId }: { siteId: string }) {
  const t = useTranslations("analytics");
  const format = useFormatter();
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();

  // Anchor the window to a single "now" captured once via the lazy initializer (which React runs a single
  // time, keeping the render pure). Reusing that fixed anchor keeps the resolved `from` — and thus the
  // query key — stable across re-renders instead of drifting every render and thrashing the cache.
  const [anchorMs] = useState(() => Date.now());

  const range = parseRange(searchParams.get("range"));
  const filter = useMemo<AnalyticsFilter>(
    () => ({ from: new Date(anchorMs - range * MS_PER_DAY).toISOString() }),
    [anchorMs, range],
  );

  const query = useSiteAnalytics(siteId, filter);
  const deltaBadge = useConsentDeltas(range);

  // Announced via a persistent aria-live region in the header (present across the loading/error/success
  // branches below) so switching the range preset is confirmed to screen-reader users without them having
  // to re-discover the freshly rendered figures themselves.
  const statusText = query.isFetching
    ? t("status.loading", { days: range })
    : query.isSuccess
      ? t("status.updated", { days: range })
      : "";

  const applyRange = useCallback(
    (days: RangeDays) => {
      const params = new URLSearchParams(searchParams);
      params.set("range", String(days));
      router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    },
    [pathname, router, searchParams],
  );

  const header = (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        <h1 id="analytics-heading" className="text-2xl font-semibold tracking-tight">
          {t("title")}
        </h1>
        <p className="max-w-2xl text-sm text-muted-foreground">{t("subtitle")}</p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <RangeSelector value={range} onChange={applyRange} />
        <ExportAnalyticsButton siteId={siteId} filter={filter} />
        <DownloadEvidencePackButton siteId={siteId} />
      </div>
      <p role="status" aria-live="polite" className="sr-only">
        {statusText}
      </p>
    </header>
  );

  if (query.isPending) {
    return (
      <main className="flex-1 p-6">
        <section aria-labelledby="analytics-heading" className="flex max-w-6xl flex-col gap-6">
          {header}
          <AnalyticsSkeleton />
        </section>
      </main>
    );
  }

  if (query.isError) {
    return (
      <main className="flex-1 p-6">
        <section aria-labelledby="analytics-heading" className="flex max-w-6xl flex-col gap-6">
          {header}
          <p role="alert" className="text-sm text-destructive">
            {t("loadError")}
          </p>
        </section>
      </main>
    );
  }

  const { consent, cookies, policy, previous } = query.data;
  const acceptShare = acceptSharePct(consent);
  const interactionRate = interactionRatePct(consent);

  const trendLegend = (
    <div className="flex flex-wrap items-center gap-3">
      <LegendSwatch color={ACTION_COLORS.acceptAll} label={t("actions.accept_all")} />
      <LegendSwatch color={ACTION_COLORS.rejectAll} label={t("actions.reject_all")} />
      <LegendSwatch color={ACTION_COLORS.custom} label={t("actions.custom")} />
    </div>
  );

  return (
    <main className="flex-1 p-6">
      <section aria-labelledby="analytics-heading" className="flex max-w-6xl flex-col gap-6">
        {header}

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <StatTile
            label={t("summary.impressions")}
            value={consent.impressions}
            hint={t("summary.impressionsHint")}
            delta={deltaBadge(impressionsDelta(consent.impressions, previous), "percent")}
          />
          <StatTile
            label={t("summary.totalEvents")}
            value={consent.totalEvents}
            delta={deltaBadge(eventsDelta(consent.totalEvents, previous), "percent")}
          />
          <StatTile
            label={t("summary.interactionRate")}
            value={`${interactionRate}%`}
            hint={t("summary.interactionRateHint")}
            delta={deltaBadge(interactionRateDelta(consent, previous), "points")}
          />
          <StatTile
            label={t("summary.acceptShare")}
            value={`${acceptShare}%`}
            delta={deltaBadge(acceptShareDelta(consent, previous), "points")}
          />
          <StatTile
            label={t("summary.policyVersion")}
            value={policy ? `v${policy.version}` : t("summary.noPolicy")}
            hint={
              policy?.publishedAt
                ? t("summary.publishedAt", {
                    when: format.dateTime(new Date(policy.publishedAt), { dateStyle: "medium" }),
                  })
                : undefined
            }
          />
          <StatTile
            label={t("summary.languages")}
            value={consent.languageSplit.length || t("summary.none")}
          />
        </div>

        <ChartCard
          title={t("consentTrend.title")}
          description={t("consentTrend.description")}
          aside={trendLegend}
        >
          {consent.trend.length > 0 ? (
            <ConsentTrendChart trend={consent.trend} />
          ) : (
            <EmptyNote>{t("consentTrend.empty")}</EmptyNote>
          )}
        </ChartCard>

        <div className="grid gap-6 lg:grid-cols-2">
          <ChartCard title={t("categoryOptIn.title")} description={t("categoryOptIn.description")}>
            {consent.categoryOptIn.length > 0 ? (
              <CategoryOptInChart categories={consent.categoryOptIn} />
            ) : (
              <EmptyNote>{t("categoryOptIn.empty")}</EmptyNote>
            )}
          </ChartCard>

          <ChartCard title={t("languages.title")} description={t("languages.description")}>
            {consent.languageSplit.length > 0 ? (
              <LanguageSplit languages={consent.languageSplit} />
            ) : (
              <EmptyNote>{t("languages.empty")}</EmptyNote>
            )}
          </ChartCard>
        </div>

        <ChartCard title={t("cookies.title")} description={t("cookies.description")}>
          {cookies ? (
            <CookieInventory cookies={cookies} />
          ) : (
            <EmptyNote>{t("cookies.empty")}</EmptyNote>
          )}
        </ChartCard>
      </section>
    </main>
  );
}
