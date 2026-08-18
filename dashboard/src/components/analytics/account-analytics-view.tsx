"use client";

import { useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import { useCallback, useMemo, useState } from "react";

import { AnalyticsSkeleton } from "@/components/analytics/analytics";
import { EmptyNote, LegendSwatch } from "@/components/analytics/analytics-primitives";
import { CategoryOptInChart } from "@/components/analytics/category-opt-in-chart";
import { ChartCard } from "@/components/analytics/chart-card";
import { ACTION_COLORS } from "@/components/analytics/chart-theme";
import { ConsentTrendChart } from "@/components/analytics/consent-trend-chart";
import { LanguageSplit } from "@/components/analytics/language-split";
import { parseRange, type RangeDays, RangeSelector } from "@/components/analytics/range-selector";
import { StatTile } from "@/components/analytics/stat-tile";
import { useConsentDeltas } from "@/components/analytics/use-consent-deltas";
import { useEntitlementGate } from "@/components/analytics/use-entitlement-gate";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useAccountAnalytics } from "@/hooks/use-account-analytics";
import { usePathname, useRouter } from "@/i18n/navigation";
import { acceptShareDelta, acceptSharePct, eventsDelta } from "@/lib/analytics/delta";
import type { AnalyticsFilter } from "@/lib/api/analytics";

const MS_PER_DAY = 86_400_000;

/**
 * Cross-site ("All Sites") consent roll-up — the account-level counterpart of {@link Analytics}. Every
 * figure is one aggregated read across *all* of the account's sites: consent totals/mix, the daily trend,
 * per-category opt-in, and the visitor-language split. Consent-only in this slice (no cookies/policy/CSV).
 *
 * A Pro/Business feature. The real gate is server-side (403 `CROSS_SITE_ANALYTICS_NOT_ENTITLED`); this view
 * mirrors it for display. Branch order: entitlement still resolving → skeleton; entitlement fetch failed →
 * error (a paying account is never told to upgrade on a transient failure); entitlement resolved-but-absent
 * → `<LockedFeature>` upgrade prompt; then the roll-up's own loading/error/success. The roll-up read only
 * fires once entitlement is confirmed (`enabled` gate), so a locked account issues no guaranteed-403 request.
 * The window preset lives in the URL (`?range=`, shareable) and resolves to a concrete `from`; `to` is left
 * open so the backend anchors it.
 */
export function AccountAnalyticsView() {
  const t = useTranslations("analytics");
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const router = useRouter();
  const gate = useEntitlementGate((limits) => limits.crossSiteAnalytics);

  // Anchor the window to a single "now" captured once (lazy initializer runs a single time, keeping the
  // render pure), so the resolved `from` — and thus the query key — stays stable across re-renders.
  const [anchorMs] = useState(() => Date.now());
  const range = parseRange(searchParams.get("range"));
  const filter = useMemo<AnalyticsFilter>(
    () => ({ from: new Date(anchorMs - range * MS_PER_DAY).toISOString() }),
    [anchorMs, range],
  );

  // `entitled` is only true once the entitlement resolves in our favour; gate the roll-up read on it so
  // a locked, still-loading, or errored account never fires the request the backend would 403 anyway.
  const entitled = gate.status === "entitled";
  const query = useAccountAnalytics(filter, { enabled: entitled });
  const deltaBadge = useConsentDeltas(range);

  const applyRange = useCallback(
    (days: RangeDays) => {
      const params = new URLSearchParams(searchParams);
      params.set("range", String(days));
      router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    },
    [pathname, router, searchParams],
  );

  const statusText = query.isFetching
    ? t("status.loading", { days: range })
    : query.isSuccess
      ? t("status.updated", { days: range })
      : "";

  const header = (
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        <h1 id="account-analytics-heading" className="text-2xl font-semibold tracking-tight">
          {t("crossSite.title")}
        </h1>
        <p className="max-w-2xl text-sm text-muted-foreground">{t("crossSite.subtitle")}</p>
      </div>
      {entitled ? <RangeSelector value={range} onChange={applyRange} /> : null}
      <p role="status" aria-live="polite" className="sr-only">
        {statusText}
      </p>
    </header>
  );

  const shell = (children: React.ReactNode) => (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="account-analytics-heading"
        className="flex max-w-6xl flex-col gap-6"
      >
        {header}
        {children}
      </section>
    </main>
  );

  // Entitlement still resolving → skeleton, so the gate stays closed until we actually know the plan.
  if (gate.status === "pending") {
    return shell(<AnalyticsSkeleton />);
  }
  // Entitlement fetch itself failed → error, not the upgrade prompt: a Pro/Business account must never be
  // told to upgrade because a transient request failed. Distinct from resolved-but-not-entitled below.
  if (gate.status === "error") {
    return shell(
      <p role="alert" className="text-sm text-destructive">
        {t("crossSite.loadError")}
      </p>,
    );
  }
  // Plan gate: entitlement resolved and this account doesn't have it → upgrade prompt, never the dashboard.
  if (gate.status === "locked") {
    return shell(
      <LockedFeature label={t("crossSite.title")} reason={t("crossSite.locked")} />,
    );
  }

  if (query.isPending) {
    return shell(<AnalyticsSkeleton />);
  }
  if (query.isError) {
    return shell(
      <p role="alert" className="text-sm text-destructive">
        {t("crossSite.loadError")}
      </p>,
    );
  }

  const { consent, siteCount, previous } = query.data;
  const acceptShare = acceptSharePct(consent);

  const trendLegend = (
    <div className="flex flex-wrap items-center gap-3">
      <LegendSwatch color={ACTION_COLORS.acceptAll} label={t("actions.accept_all")} />
      <LegendSwatch color={ACTION_COLORS.rejectAll} label={t("actions.reject_all")} />
      <LegendSwatch color={ACTION_COLORS.custom} label={t("actions.custom")} />
    </div>
  );

  return shell(
    <>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatTile label={t("crossSite.siteCount")} value={siteCount} />
        <StatTile
          label={t("summary.totalEvents")}
          value={consent.totalEvents}
          delta={deltaBadge(eventsDelta(consent.totalEvents, previous), "percent")}
        />
        <StatTile
          label={t("summary.acceptShare")}
          value={`${acceptShare}%`}
          delta={deltaBadge(acceptShareDelta(consent, previous), "points")}
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
    </>,
  );
}
