"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmbedSnippet } from "@/components/sites/embed-snippet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { useArchiveSite, useSite } from "@/hooks/use-sites";
import { useRouter } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";

export function SiteDetail({ siteId }: { siteId: string }) {
  const t = useTranslations("sites");
  const tErrors = useTranslations("auth.errors");
  const router = useRouter();
  const site = useSite(siteId);
  const archive = useArchiveSite(siteId);
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);

  const handleArchive = async () => {
    try {
      await archive.mutateAsync();
      toast.success(t("detail.archivedToast"));
      router.push("/sites");
    } catch (error) {
      setIsConfirmOpen(false);
      toast.error(tErrors(getApiErrorCode(error)));
    }
  };

  if (site.isPending) {
    return (
      <main className="flex-1 p-6" aria-busy="true">
        <div className="flex flex-col gap-4">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      </main>
    );
  }

  if (site.isError) {
    return (
      <main className="flex-1 p-6">
        <p role="alert" className="text-sm text-destructive">
          {t("detail.loadError")}
        </p>
      </main>
    );
  }

  const data = site.data;

  return (
    <main className="flex-1 p-6">
      <section
        aria-labelledby="site-detail-heading"
        className="flex max-w-3xl flex-col gap-6"
      >
        <header className="flex flex-wrap items-center gap-3">
          <h1
            id="site-detail-heading"
            className="text-2xl font-semibold tracking-tight"
          >
            {data.domain}
          </h1>
          <Badge variant={data.status === "active" ? "secondary" : "outline"}>
            {t(`status.${data.status}`)}
          </Badge>
          <Badge variant={data.verifiedAt ? "default" : "outline"}>
            {data.verifiedAt ? t("verifiedBadge") : t("unverifiedBadge")}
          </Badge>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.siteKey")}</CardTitle>
            <CardDescription>{t("detail.siteKeyHint")}</CardDescription>
          </CardHeader>
          <CardContent>
            <code className="rounded bg-muted px-2 py-1 font-mono text-sm">
              {data.siteKey}
            </code>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.embedTitle")}</CardTitle>
            <CardDescription>{t("detail.embedDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <EmbedSnippet snippet={data.embedSnippet} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.dangerZone")}</CardTitle>
            <CardDescription>{t("detail.archiveHint")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Dialog open={isConfirmOpen} onOpenChange={setIsConfirmOpen}>
              <DialogTrigger
                render={
                  <Button variant="destructive">{t("detail.archive")}</Button>
                }
              />
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>{t("detail.archiveConfirmTitle")}</DialogTitle>
                  <DialogDescription>
                    {t("detail.archiveConfirmDescription", {
                      domain: data.domain,
                    })}
                  </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => setIsConfirmOpen(false)}
                  >
                    {t("detail.cancel")}
                  </Button>
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={() => void handleArchive()}
                    disabled={archive.isPending}
                  >
                    {t("detail.archiveConfirm")}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </CardContent>
        </Card>
      </section>
    </main>
  );
}
