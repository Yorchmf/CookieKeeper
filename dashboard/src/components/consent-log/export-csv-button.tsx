"use client";

import { useTranslations } from "next-intl";

import { Button, buttonVariants } from "@/components/ui/button";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useEntitlement } from "@/hooks/use-billing";
import { consentExportPath, type ConsentLogFilters } from "@/lib/api/consent";

/**
 * CSV export trigger, gated on the Business-plan `csvExport` entitlement. The gate here is display-only
 * — the backend enforces it (403) — so a non-entitled user sees a `<LockedFeature>` (focusable, announced
 * as unavailable, with a readable reason + upgrade link) rather than a dead `disabled` button they can
 * neither reach nor understand. The download itself is a same-origin `<a download>`, so the browser
 * streams straight from the backend with the auth cookies attached.
 */
export function ExportCsvButton({
  siteId,
  filters,
}: {
  siteId: string;
  filters: ConsentLogFilters;
}) {
  const t = useTranslations("consentLog.export");
  const entitlement = useEntitlement();

  if (entitlement.isPending) {
    // Transient load, not a gated state: keep it focusable and mark it busy rather than hard-`disabled`.
    return (
      <Button variant="outline" aria-disabled="true" aria-busy="true">
        {t("label")}
      </Button>
    );
  }

  if (!entitlement.data?.limits.csvExport) {
    return <LockedFeature label={t("label")} reason={t("businessOnly")} />;
  }

  // A plain `<a>` wearing the button's classes rather than `<Button render={<a/>}>`: a download is a
  // link, and Base UI's button would stamp `type`/`role="button"` on the anchor (see `ButtonLink`).
  return (
    <a
      href={consentExportPath(siteId, filters)}
      download
      className={buttonVariants({ variant: "outline" })}
    >
      {t("label")}
    </a>
  );
}
