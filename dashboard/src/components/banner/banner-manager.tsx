"use client";

import { useTranslations } from "next-intl";
import { BannerEditor } from "@/components/banner/banner-editor";
import { CopyBannerCard } from "@/components/banner/copy-banner-card";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useBannerConfig } from "@/hooks/use-banner";

/** Dashboard banner-customizer surface: loads the current config, then hands it to the editor. */
export function BannerManager({ siteId }: { siteId: string }) {
  const t = useTranslations("banner");
  const banner = useBannerConfig(siteId);

  if (banner.isPending) {
    return (
      <main className="flex-1 p-6" aria-busy="true">
        <div className="flex max-w-5xl flex-col gap-4">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-64 w-full" />
        </div>
      </main>
    );
  }

  if (banner.isError) {
    return (
      <main className="flex-1 p-6">
        <p role="alert" className="text-sm text-destructive">
          {t("loadError")}
        </p>
      </main>
    );
  }

  return (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="banner-heading"
        className="flex max-w-5xl flex-col gap-6"
      >
        <header className="flex flex-col gap-1">
          <h1
            id="banner-heading"
            className="text-2xl font-semibold tracking-tight"
          >
            {t("title")}
          </h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </header>

        {banner.data ? (
          <>
            {/* Key on the published version so a successful publish (which returns a re-normalized
                document) remounts the editor and re-seeds its local state instead of drifting. */}
            <BannerEditor
              key={banner.data.version}
              siteId={siteId}
              config={banner.data}
            />
            {/* After the editor: copying is what you reach for once this banner is the way you want it. */}
            <CopyBannerCard siteId={siteId} />
          </>
        ) : (
          <Card>
            <CardHeader>
              <CardTitle>{t("empty.title")}</CardTitle>
              <CardDescription>{t("empty.description")}</CardDescription>
            </CardHeader>
          </Card>
        )}
      </section>
    </main>
  );
}
