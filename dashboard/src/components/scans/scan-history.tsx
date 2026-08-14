"use client";

import { useFormatter, useTranslations } from "next-intl";
import { RescanButton } from "@/components/scans/rescan-button";
import { ScanStatusBadge } from "@/components/scans/scan-status-badge";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useScanSchedule, useScans } from "@/hooks/use-scans";
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
        {scan.newCookieCount != null && scan.newCookieCount > 0 && (
          <Badge variant="secondary">
            {t("diff.badge", { count: scan.newCookieCount })}
          </Badge>
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

/**
 * The one line that answers the question this card actually raises: "when does the next scan run?".
 *
 * The backend owns the answer (`ScanScheduleService`) because it is the same instant the nightly job
 * gates on — computing it here from the plan cadence would let the promise drift from the job. Renders
 * nothing until it has loaded: no line at all beats a date we are not sure about.
 */
function ScheduleNote({ siteId }: { siteId: string }) {
  const t = useTranslations("scans.history");
  const format = useFormatter();
  const schedule = useScanSchedule(siteId);
  // One line's worth of space is held from the first paint so the answer arriving does not push the
  // card's content down.
  const line = "block min-h-[1lh]";

  if (!schedule.data) {
    return <span className={line} />;
  }
  const { scheduled, frequency, nextScanAt, reason } = schedule.data;

  // Archived site, lapsed account, or a trial that ends before the next cycle: the job would never come
  // back, so name the cause instead of a date. `reason` and `frequency` are backend tokens typed as
  // closed unions, so a value shipped ahead of its message stays silent rather than rendering an error.
  if (!scheduled) {
    return (
      <span className={line}>
        {reason && t.has(`paused.${reason}`) ? t(`paused.${reason}`) : null}
      </span>
    );
  }

  const dueAt = nextScanAt ? new Date(nextScanAt) : null;
  // A never-scanned site (no date) and an overdue one both resolve the same way for the customer:
  // it happens in the next nightly run, not on some future date. "Now" is the query's own fetch
  // timestamp rather than `Date.now()` — reading the clock during render is impure, and the moment
  // the answer was fetched is exactly the clock this answer was true at.
  const isDueNow = dueAt === null || dueAt.getTime() <= schedule.dataUpdatedAt;

  return (
    <span className={line}>
      {frequency && t.has(`cadence.${frequency}`)
        ? `${t(`cadence.${frequency}`)} `
        : null}
      {isDueNow
        ? t("nextScan.due")
        : t("nextScan.on", {
            date: format.dateTime(dueAt, {
              year: "numeric",
              month: "long",
              day: "numeric",
            }),
          })}
    </span>
  );
}

export function ScanHistory({ siteId }: { siteId: string }) {
  const t = useTranslations("scans");
  const scans = useScans(siteId, HISTORY_LIMIT);

  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t("history.title")}
        </CardTitle>
        <CardDescription>
          {t("history.subtitle")}
          <ScheduleNote siteId={siteId} />
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
