"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useEntitlement } from "@/hooks/use-billing";
import { analyticsExportPath, type AnalyticsFilter } from "@/lib/api/analytics";

/**
 * CSV export trigger for the consent trend, gated on the Business-plan `csvExport` entitlement. The gate
 * here is display-only — the backend enforces it (403) — so a non-entitled user sees a `<LockedFeature>`
 * (focusable, announced, with an upgrade link) rather than a dead `disabled` button. The download itself
 * is a same-origin `<a download>`, so the browser streams straight from the backend with auth cookies
 * attached. The export honours the active window.
 */
export function ExportAnalyticsButton({
  siteId,
  filter,
}: {
  siteId: string;
  filter: AnalyticsFilter;
}) {
  const t = useTranslations("analytics.export");
  const entitlement = useEntitlement();

  if (entitlement.isPending) {
    return (
      <Button variant="outline" aria-disabled="true" aria-busy="true">
        {t("label")}
      </Button>
    );
  }

  if (!entitlement.data?.limits.csvExport) {
    return <LockedFeature label={t("label")} reason={t("businessOnly")} />;
  }

  return (
    <Button variant="outline" render={<a href={analyticsExportPath(siteId, filter)} download />}>
      {t("label")}
    </Button>
  );
}
