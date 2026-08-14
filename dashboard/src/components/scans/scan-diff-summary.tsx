"use client";

import { useFormatter, useTranslations } from "next-intl";
import { useId } from "react";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { ScanDiff } from "@/lib/api/scans";

/**
 * "What changed since your last scan" for a completed scan (ScanDiffResponse). Rendered only when
 * there is a previous completed scan to compare against (`hasPrevious`); the site's first scan shows
 * nothing. The headline is the new-cookie count — the signal a customer acts on — with removed
 * cookies and the marketing-tracker delta as supporting detail. Cookie names come from the backend
 * diff (compared by name, not row identity), so the list is stable across re-runs.
 */
export function ScanDiffSummary({ diff }: { diff: ScanDiff }) {
  const t = useTranslations("scans.diff");
  const format = useFormatter();
  const addedLabelId = useId();
  const removedLabelId = useId();

  // Show a comparison only when there is a dated previous scan to anchor it to. `hasPrevious` and
  // `previousScanAt` always travel together from the backend (the only baseline case leaves both
  // null), but the type allows them to diverge — bail rather than render "…since your last scan on ".
  if (!diff.hasPrevious || !diff.previousScanAt) return null;

  const previousDate = format.dateTime(new Date(diff.previousScanAt), {
    year: "numeric",
    month: "short",
    day: "numeric",
  });

  const trackerDelta = diff.trackerCountDelta ?? 0;

  return (
    <Card>
      <CardHeader>
        <CardTitle role="heading" aria-level={2}>
          {t("title")}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          {diff.newCookieCount > 0
            ? t("summaryNew", {
                count: diff.newCookieCount,
                date: previousDate,
              })
            : t("summaryNone", { date: previousDate })}
        </p>

        {trackerDelta !== 0 && (
          <p className="text-sm">
            {trackerDelta > 0
              ? t("trackersUp", { count: trackerDelta })
              : t("trackersDown", { count: -trackerDelta })}
          </p>
        )}

        {diff.addedCookieNames.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <span
              id={addedLabelId}
              className="text-xs font-medium text-muted-foreground"
            >
              {t("addedLabel")}
            </span>
            <ul
              aria-labelledby={addedLabelId}
              className="flex flex-wrap gap-1.5"
            >
              {diff.addedCookieNames.map((name) => (
                <li key={name}>
                  <Badge variant="secondary" className="font-mono">
                    {name}
                  </Badge>
                </li>
              ))}
            </ul>
          </div>
        )}

        {diff.removedCookieNames.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <span
              id={removedLabelId}
              className="text-xs font-medium text-muted-foreground"
            >
              {t("removedLabel")}
            </span>
            <ul
              aria-labelledby={removedLabelId}
              className="flex flex-wrap gap-1.5"
            >
              {diff.removedCookieNames.map((name) => (
                <li key={name}>
                  <Badge
                    variant="outline"
                    className="font-mono text-muted-foreground line-through"
                  >
                    {name}
                  </Badge>
                </li>
              ))}
            </ul>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
