"use client";

import { useTranslations } from "next-intl";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useSites } from "@/hooks/use-sites";

/** Overview card showing the live count of registered sites. */
export function SitesCountCard() {
  const t = useTranslations("dashboard");
  const sites = useSites();

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-baseline gap-2">
          {t("cards.sites")}
          {sites.isPending ? (
            <Skeleton className="h-6 w-8" />
          ) : (
            <span className="text-2xl font-semibold tabular-nums">
              {sites.isError ? "—" : sites.data.total}
            </span>
          )}
        </CardTitle>
        <CardDescription>{t("cards.sitesHint")}</CardDescription>
      </CardHeader>
    </Card>
  );
}
