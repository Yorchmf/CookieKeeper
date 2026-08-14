"use client";

import { useFormatter, useTranslations } from "next-intl";
import { useMemo, useState } from "react";

import { StatTile } from "@/components/analytics/stat-tile";
import { AttentionList } from "@/components/dashboard/attention-list";
import { OnboardingChecklist } from "@/components/dashboard/onboarding-checklist";
import { TrialStrip } from "@/components/dashboard/trial-strip";
import { Skeleton } from "@/components/ui/skeleton";
import { useOverview } from "@/hooks/use-overview";
import type { AnalyticsFilter } from "@/lib/api/analytics";
import type { OnboardingProgress } from "@/lib/api/overview";

const MS_PER_DAY = 86_400_000;
const WINDOW_DAYS = 30;

/**
 * Whether every getting-started step is done. The payload carries no `complete` flag by design — it is this
 * trivial `&&`, derived on the client so there is one fewer field to keep in sync with the backend.
 */
function isOnboardingComplete(onboarding: OnboardingProgress): boolean {
  return (
    onboarding.addedSite &&
    onboarding.scanned &&
    onboarding.customisedBanner &&
    onboarding.verified
  );
}

/** Loading placeholder mirroring the real layout so the page doesn't jump when data lands. */
function OverviewSkeleton() {
  return (
    <div className="flex flex-col gap-6" aria-hidden="true">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <Skeleton key={index} className="h-24 w-full rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-40 w-full rounded-xl" />
    </div>
  );
}

/**
 * The dashboard home. One aggregated read gives the cross-site headline figures and the severity-ordered
 * list of what needs the customer next; the billing strip comes from the entitlement query so billing
 * state has a single source of truth.
 *
 * The window is fixed at the trailing 30 days — this is the "how are things" page, not the analysis
 * surface. Per-site analytics keeps the adjustable range (and a shareable `?range=` in the URL).
 */
export function DashboardHome() {
  const t = useTranslations("dashboard");

  // Anchor "now" once via the lazy initializer (which React runs a single time, keeping render pure), so
  // the resolved `from` — and therefore the query key — stays stable instead of drifting every render.
  const [anchorMs] = useState(() => Date.now());
  const filter = useMemo<AnalyticsFilter>(
    () => ({ from: new Date(anchorMs - WINDOW_DAYS * MS_PER_DAY).toISOString() }),
    [anchorMs],
  );

  const query = useOverview(filter);

  return (
    <main className="flex-1 p-6" aria-busy={query.isPending}>
      <section aria-labelledby="dashboard-heading" className="flex max-w-5xl flex-col gap-6">
        <header className="flex flex-col gap-1">
          <h1 id="dashboard-heading" className="text-2xl font-semibold tracking-tight">
            {t("title")}
          </h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </header>

        <TrialStrip />

        {query.isPending ? (
          <OverviewSkeleton />
        ) : query.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {t("loadError")}
          </p>
        ) : !isOnboardingComplete(query.data.onboarding) ? (
          // Setup isn't finished: guide the customer through the funnel instead of a health summary. The
          // headline only appears once a site exists (there is nothing to summarise before that), and the
          // attention list stays hidden — it would just echo the same "verify / scan" steps.
          <>
            {query.data.headline.activeSites > 0 ? (
              <Headline
                activeSites={query.data.headline.activeSites}
                consentEvents={query.data.headline.consentEvents}
                acceptAllRate={query.data.headline.acceptAllRate}
                cookiesFound={query.data.headline.cookiesFound}
                lastScanAt={query.data.headline.lastScanAt}
              />
            ) : null}
            <OnboardingChecklist progress={query.data.onboarding} />
          </>
        ) : (
          <>
            <Headline
              activeSites={query.data.headline.activeSites}
              consentEvents={query.data.headline.consentEvents}
              acceptAllRate={query.data.headline.acceptAllRate}
              cookiesFound={query.data.headline.cookiesFound}
              lastScanAt={query.data.headline.lastScanAt}
            />
            <section aria-labelledby="attention-heading" className="flex flex-col gap-3">
              <header className="flex flex-col gap-0.5">
                <h2 id="attention-heading" className="text-lg font-semibold tracking-tight">
                  {t("attention.title")}
                </h2>
                <p className="text-sm text-muted-foreground">{t("attention.description")}</p>
              </header>
              <AttentionList actions={query.data.actions} />
            </section>
          </>
        )}
      </section>
    </main>
  );
}

/** Four cross-site figures. Consent figures are windowed; cookie/scan figures are point-in-time. */
function Headline({
  activeSites,
  consentEvents,
  acceptAllRate,
  cookiesFound,
  lastScanAt,
}: {
  activeSites: number;
  consentEvents: number;
  acceptAllRate: number | null;
  cookiesFound: number;
  lastScanAt: string | null;
}) {
  const t = useTranslations("dashboard.headline");
  const format = useFormatter();

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatTile label={t("sites")} value={activeSites} hint={t("sitesHint")} />
      <StatTile
        label={t("consents")}
        value={format.number(consentEvents)}
        hint={t("consentsHint", { days: WINDOW_DAYS })}
      />
      <StatTile
        label={t("acceptRate")}
        // Absent, not zero, when nothing was recorded: an em dash says "no data", 0% would be a claim.
        value={acceptAllRate != null ? format.number(acceptAllRate, { style: "percent" }) : "—"}
        hint={t("acceptRateHint")}
      />
      <StatTile
        label={t("cookies")}
        value={cookiesFound}
        hint={
          lastScanAt != null
            ? t("cookiesHint", {
                date: format.dateTime(new Date(lastScanAt), {
                  year: "numeric",
                  month: "short",
                  day: "numeric",
                }),
              })
            : t("cookiesHintNever")
        }
      />
    </div>
  );
}
