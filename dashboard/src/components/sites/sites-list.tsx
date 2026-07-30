"use client";

import { useFormatter, useTranslations } from "next-intl";
import { AddSiteDialog } from "@/components/sites/add-site-dialog";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { useSites } from "@/hooks/use-sites";
import { Link } from "@/i18n/navigation";
import type { Site } from "@/lib/api/sites";

function SiteRow({ site }: { site: Site }) {
  const t = useTranslations("sites");
  const format = useFormatter();

  return (
    <li>
      <Link
        href={`/sites/${site.id}`}
        className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card px-4 py-3 transition-colors hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 outline-none"
      >
        <span className="min-w-0 flex-1 truncate font-medium">
          {site.domain}
        </span>
        <Badge variant={site.status === "active" ? "secondary" : "outline"}>
          {t(`status.${site.status}`)}
        </Badge>
        <Badge variant={site.verifiedAt ? "default" : "outline"}>
          {site.verifiedAt ? t("verifiedBadge") : t("unverifiedBadge")}
        </Badge>
        <span className="text-sm text-muted-foreground">
          {format.dateTime(new Date(site.createdAt), {
            year: "numeric",
            month: "short",
            day: "numeric",
          })}
        </span>
      </Link>
    </li>
  );
}

export function SitesList() {
  const t = useTranslations("sites");
  const sites = useSites();

  return (
    <main className="flex-1 p-6">
      <section aria-labelledby="sites-heading" className="flex flex-col gap-6">
        <header className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1
              id="sites-heading"
              className="text-2xl font-semibold tracking-tight"
            >
              {t("title")}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {t("subtitle")}
            </p>
          </div>
          <AddSiteDialog />
        </header>

        {sites.isPending ? (
          <div className="flex flex-col gap-3" aria-hidden="true">
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
          </div>
        ) : sites.isError ? (
          <p role="alert" className="text-sm text-destructive">
            {t("loadError")}
          </p>
        ) : sites.data.sites.length === 0 ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border p-10 text-center">
            <p className="font-medium">{t("empty.title")}</p>
            <p className="max-w-md text-sm text-muted-foreground">
              {t("empty.description")}
            </p>
          </div>
        ) : (
          <ul className="flex flex-col gap-3">
            {sites.data.sites.map((site) => (
              <SiteRow key={site.id} site={site} />
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
