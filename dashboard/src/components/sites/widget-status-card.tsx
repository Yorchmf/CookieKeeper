"use client";

import { CheckCircle2Icon, CircleDashedIcon, ClockIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useWidgetStatus } from "@/hooks/use-widget-status";
import type { WidgetStatusState } from "@/lib/api/widget-status";

/** Per-state chrome: the badge tone and the non-color icon that carries the same meaning. */
const STATE_STYLES: Record<
  WidgetStatusState,
  { variant: "default" | "secondary" | "outline"; Icon: typeof CheckCircle2Icon }
> = {
  active: { variant: "default", Icon: CheckCircle2Icon },
  idle: { variant: "secondary", Icon: ClockIcon },
  never_seen: { variant: "outline", Icon: CircleDashedIcon },
};

/**
 * "Did my embed actually work?" — the confirmation the dashboard owed the customer between pasting the
 * snippet and the first consent event.
 *
 * The evidence is the banner-impression counter the widget itself feeds, which constrains the copy in two
 * ways this component is careful about:
 *  - **Day granularity** (the counter stores a UTC calendar day, never a finer timestamp): the strongest
 *    claim available is "seen today", so nothing here says "2 minutes ago".
 *  - **Only undecided visitors count.** A returning visitor with a stored choice sees no banner and fires
 *    no beacon, so quiet is genuinely ambiguous. The `idle` copy therefore offers *both* readings — normal
 *    if visitors already chose, worth checking if new traffic is arriving — instead of calling it broken.
 *
 * Complements [VerifySiteCard]: verification is a one-shot proof of domain control, this is the ongoing
 * "is it still rendering" signal. A site can pass one and fail the other in either direction.
 *
 * Accessibility: the badge state is carried by an icon and text, never colour alone (SC 1.4.1), and a
 * **stable** `role="status"` region — rendered outside every branch, so the node survives loading and
 * error swaps — announces the settled verdict after a manual re-check (SC 4.1.3).
 */
export function WidgetStatusCard({ siteId }: { siteId: string }) {
  const t = useTranslations("sites.detail.widgetStatus");
  const { data, isPending, isError, refetch, isFetching } =
    useWidgetStatus(siteId);
  const [announcement, setAnnouncement] = useState("");

  const handleRecheck = async () => {
    if (isFetching) return;
    // Cleared first so re-checking into the *same* verdict is still a text mutation the AT re-reads.
    setAnnouncement("");
    const result = await refetch();
    setAnnouncement(
      result.data ? t(`badge.${result.data.state}`) : t("loadError"),
    );
  };

  const { variant, Icon } = STATE_STYLES[data?.state ?? "never_seen"];

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center gap-2">
            {t("title")}
            {data && (
              <Badge variant={variant}>
                <Icon aria-hidden="true" className="mr-1 size-3.5" />
                {t(`badge.${data.state}`)}
              </Badge>
            )}
          </CardTitle>
          <CardDescription>{t("description")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          {isPending && <Skeleton className="h-5 w-3/4" />}

          {isError && (
            <p className="text-sm text-muted-foreground">{t("loadError")}</p>
          )}

          {data && (
            <>
              <p className="text-sm text-muted-foreground">
                {data.state === "active"
                  ? t("body.active", {
                      day: data.lastSeenDay ?? "",
                      today: data.impressionsToday,
                      window: data.impressionsInWindow,
                      days: data.windowDays,
                    })
                  : data.state === "idle"
                    ? t("body.idle", {
                        day: data.lastSeenDay ?? "",
                        days: data.windowDays,
                      })
                    : t("body.never_seen")}
              </p>
              {/* The one caveat that keeps every number above honest — stated once, not repeated per state. */}
              <p className="text-xs text-muted-foreground">{t("note")}</p>
            </>
          )}

          <div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              aria-disabled={isFetching}
              aria-busy={isFetching}
              onClick={() => void handleRecheck()}
            >
              {isFetching ? t("checking") : t("checkAgain")}
            </Button>
          </div>
        </CardContent>
      </Card>
      <span role="status" aria-live="polite" className="sr-only">
        {announcement}
      </span>
    </>
  );
}
