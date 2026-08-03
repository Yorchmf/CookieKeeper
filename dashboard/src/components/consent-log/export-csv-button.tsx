"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { useEntitlement } from "@/hooks/use-billing";
import { Link } from "@/i18n/navigation";
import { consentExportPath, type ConsentLogFilters } from "@/lib/api/consent";

/**
 * CSV export trigger, gated on the Business-plan `csvExport` entitlement. The gate here is display-only
 * — the backend enforces it (403) — so a non-entitled user sees a disabled button plus an upgrade link
 * rather than a dead download. The download itself is a same-origin `<a download>`, so the browser
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
    return (
      <Button variant="outline" disabled>
        {t("label")}
      </Button>
    );
  }

  if (!entitlement.data?.limits.csvExport) {
    return (
      <div className="flex flex-col items-end gap-1">
        <Button variant="outline" disabled>
          {t("label")}
        </Button>
        <span className="text-xs text-muted-foreground">
          {t("businessOnly")}{" "}
          <Link
            href="/billing"
            className="font-medium text-foreground underline underline-offset-2"
          >
            {t("upgrade")}
          </Link>
        </span>
      </div>
    );
  }

  return (
    <Button variant="outline" render={<a href={consentExportPath(siteId, filters)} download />}>
      {t("label")}
    </Button>
  );
}
