"use client";

import { useTranslations } from "next-intl";

import { useEntitlementGate } from "@/components/analytics/use-entitlement-gate";
import { Button, buttonVariants } from "@/components/ui/button";
import { EntitlementGateError } from "@/components/ui/entitlement-gate-error";
import { LockedFeature } from "@/components/ui/locked-feature";
import { analyticsExportPath, type AnalyticsFilter } from "@/lib/api/analytics";

/**
 * CSV export trigger for the consent trend, gated on the Business-plan `csvExport` entitlement. The gate
 * here is display-only — the backend enforces it (403). Gate states come from {@link useEntitlementGate}
 * so a *failed* entitlement fetch is never rendered as "not entitled": `error` shows a retry, distinct
 * from `locked`, which shows the `<LockedFeature>` upgrade prompt. The download itself is a same-origin
 * `<a download>`, so the browser streams straight from the backend with auth cookies attached. The
 * export honours the active window.
 */
export function ExportAnalyticsButton({
  siteId,
  filter,
}: {
  siteId: string;
  filter: AnalyticsFilter;
}) {
  const t = useTranslations("analytics.export");
  const gate = useEntitlementGate((limits) => limits.csvExport);

  if (gate.status === "pending") {
    return (
      <Button variant="outline" aria-disabled="true" aria-busy="true">
        {t("label")}
      </Button>
    );
  }

  if (gate.status === "error") {
    return <EntitlementGateError label={t("label")} onRetry={gate.retry} />;
  }

  if (gate.status === "locked") {
    return <LockedFeature label={t("label")} reason={t("businessOnly")} />;
  }

  // A plain `<a>` wearing the button's classes rather than `<Button render={<a/>}>`: a download is a
  // link, and Base UI's button would stamp `type`/`role="button"` on the anchor (see `ButtonLink`).
  return (
    <a
      href={analyticsExportPath(siteId, filter)}
      download
      className={buttonVariants({ variant: "outline" })}
    >
      {t("label")}
    </a>
  );
}
