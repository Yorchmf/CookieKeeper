"use client";

import { useFormatter, useTranslations } from "next-intl";
import { RescanButton } from "@/components/scans/rescan-button";
import { ScanStatusBadge } from "@/components/scans/scan-status-badge";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useEntitlement } from "@/hooks/use-billing";
import { useScans } from "@/hooks/use-scans";
import { Link } from "@/i18n/navigation";
import type { ScanSummary } from "@/lib/api/scans";

/** How many recent scans the site-detail history card surfaces. */
const HISTORY_LIMIT = 10;

function ScanRow({ siteId, scan }: { siteId: string; scan: ScanSummary }) {
  const t = useTranslations("scans");
  const format = useFormatter();
  const when = scan.finishedAt ?? scan.createdAt;
  // A failed scan carries a stable machine token in `scan.error`; map it to localized copy so the user
  // learns *why* it failed, falling back to the generic reason for a token the UI doesn't map yet.
  const errorMessage =
    scan.status === "failed" && scan.error
      ? t.has(`errors.${scan.error}`)
        ? t(`errors.${scan.error}`)
        : t("errors.internal_error")
      : null;

  return (
    <li>
      <Link
        href={`/sites/${siteId}/scans/${scan.id}`}
        className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 transition-colors hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 outline-none"
      >
        <span className="min-w-0 flex-1 truncate text-sm font-medium">
          {format.dateTime(new Date(when), {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
          })}
        </span>
        {scan.pagesCrawled != null && (
          <span className="text-sm text-muted-foreground">
            {t("history.pagesCrawled", { count: scan.pagesCrawled })}
          </span>
        )}
        <ScanStatusBadge status={scan.status} />
        {errorMessage && (
          <span className="basis-full text-sm text-destructive">
            {errorMessage}
          </span>
        )}
      </Link>
    </li>
  );
}

export function ScanHistory({ siteId }: { siteId: string }) {
  const t = useTranslations("scans");
  const scans = useScans(siteId, HISTORY_LIMIT);
  // The plan decides how often the scheduler comes back. Stating it here answers the question this
  // card actually raises ("when does the next one run?") and explains a history that looks stale.
  // Rendered only once the entitlement has loaded — a wrong cadence is worse than a missing line.
  const entitlement = useEntitlement();
  const cadence = entitlement.data?.limits.rescanFrequency;

  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t("history.title")}
        </CardTitle>
        <CardDescription>
          {t("history.subtitle")}
          {cadence ? (
            <span className="block">{t(`history.cadence.${cadence}`)}</span>
          ) : null}
        </CardDescription>
        <CardAction>
          <RescanButton siteId={siteId} />
        </CardAction>
      </CardHeader>
      <CardContent>
        {scans.isPending ? (
          <div className="flex flex-col gap-3" aria-hidden="true">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : scans.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {t("history.loadError")}
          </p>
        ) : scans.data.scans.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("history.empty")}</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {scans.data.scans.map((scan) => (
              <ScanRow key={scan.id} siteId={siteId} scan={scan} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
