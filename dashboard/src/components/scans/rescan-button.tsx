"use client";

import { RefreshCwIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useEntitlement } from "@/hooks/use-billing";
import { useRequestScan } from "@/hooks/use-scans";
import { getApiErrorCode } from "@/lib/api-error-codes";

/**
 * On-demand re-scan trigger, gated on the `onDemandRescan` entitlement (Pro/Business). A non-entitled
 * user gets a `<LockedFeature>` — the gate is display-only, the backend enforces the real 403. On
 * success we don't poll here: `useRequestScan` invalidates this site's scan list, the refetch surfaces
 * the freshly-queued row, and `useScans`'s existing 3s `refetchInterval` drives it to done. A 409
 * (`SCAN_ALREADY_IN_PROGRESS`) is informational — a live scan already covers the request — so it lands
 * as a neutral toast, not an error.
 */
export function RescanButton({ siteId }: { siteId: string }) {
  const t = useTranslations("sites.detail.rescan");
  const tErrors = useTranslations("auth.errors");
  const entitlement = useEntitlement();
  const requestScan = useRequestScan(siteId);

  if (entitlement.isPending) {
    return (
      <Button variant="outline" size="sm" aria-disabled="true" aria-busy="true">
        {t("label")}
      </Button>
    );
  }

  if (!entitlement.data?.limits.onDemandRescan) {
    return <LockedFeature label={t("label")} reason={t("locked")} size="sm" />;
  }

  const handleRescan = async () => {
    // Guard against a keyboard-dispatched click while the request is in flight (the control stays
    // focusable via aria-disabled rather than dropping out of the tab order).
    if (requestScan.isPending) return;
    try {
      await requestScan.mutateAsync();
      toast.success(t("started"));
    } catch (error) {
      const code = getApiErrorCode(error);
      if (code === "SCAN_ALREADY_IN_PROGRESS") {
        toast(t("inProgress"));
        return;
      }
      toast.error(tErrors(code));
    }
  };

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      aria-disabled={requestScan.isPending}
      aria-busy={requestScan.isPending}
      onClick={() => void handleRescan()}
    >
      <RefreshCwIcon aria-hidden="true" />
      {requestScan.isPending ? t("starting") : t("label")}
    </Button>
  );
}
