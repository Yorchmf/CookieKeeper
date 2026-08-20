"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ScanHistory } from "@/components/scans/scan-history";
import { BrandingCard } from "@/components/sites/branding-card";
import { EmbedOptionsSnippet } from "@/components/sites/embed-options-snippet";
import { RenameSiteCard } from "@/components/sites/rename-site-card";
import { VerifySiteCard } from "@/components/sites/verify-site-card";
import { WidgetStatusCard } from "@/components/sites/widget-status-card";
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
import { useArchiveSite, useRestoreSite, useSite } from "@/hooks/use-sites";
import { Link, useRouter } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";

export function SiteDetail({ siteId }: { siteId: string }) {
  const t = useTranslations("sites");
  const tErrors = useTranslations("auth.errors");
  const router = useRouter();
  const site = useSite(siteId);
  const archive = useArchiveSite(siteId);
  const restore = useRestoreSite(siteId);
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

  const handleRestore = async () => {
    try {
      await restore.mutateAsync();
      toast.success(t("detail.restoredToast"));
    } catch (error) {
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

        <VerifySiteCard site={data} />

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
            <EmbedOptionsSnippet snippet={data.embedSnippet} />
          </CardContent>
        </Card>

        {/* Directly under the snippet: the answer to "did that paste actually work?". */}
        <WidgetStatusCard siteId={siteId} />

        <ScanHistory siteId={siteId} />

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.bannerTitle")}</CardTitle>
            <CardDescription>{t("detail.bannerDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              render={<Link href={`/sites/${siteId}/banner`} />}
            >
              {t("detail.bannerCta")}
            </Button>
          </CardContent>
        </Card>

        <BrandingCard
          siteId={siteId}
          hideBranding={data.hideBranding}
          isEntitled={data.brandingRemovalEntitled}
        />

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.policyTitle")}</CardTitle>
            <CardDescription>{t("detail.policyDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              render={<Link href={`/sites/${siteId}/policy`} />}
            >
              {t("detail.policyCta")}
            </Button>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.consentLogTitle")}</CardTitle>
            <CardDescription>{t("detail.consentLogDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              render={<Link href={`/sites/${siteId}/consent-log`} />}
            >
              {t("detail.consentLogCta")}
            </Button>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("detail.analyticsTitle")}</CardTitle>
            <CardDescription>{t("detail.analyticsDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              variant="outline"
              render={<Link href={`/sites/${siteId}/analytics`} />}
            >
              {t("detail.analyticsCta")}
            </Button>
          </CardContent>
        </Card>

        <RenameSiteCard
          siteId={siteId}
          domain={data.domain}
          isVerified={data.verifiedAt !== null}
        />

        {data.status === "archived" ? (
          <Card>
            <CardHeader>
              <CardTitle>{t("detail.restoreTitle")}</CardTitle>
              <CardDescription>{t("detail.restoreHint")}</CardDescription>
            </CardHeader>
            <CardContent>
              <Button
                type="button"
                onClick={() => void handleRestore()}
                disabled={restore.isPending}
              >
                {t("detail.restore")}
              </Button>
            </CardContent>
          </Card>
        ) : (
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
        )}
      </section>
    </main>
  );
}
