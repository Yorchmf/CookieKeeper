"use client";

import * as Sentry from "@sentry/nextjs";
import { FileArchiveIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button, buttonVariants } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useEntitlement } from "@/hooks/use-billing";
import { evidencePackPath } from "@/lib/api/analytics";
import { cn } from "@/lib/utils";

/**
 * Compliance evidence pack download, gated on the Business-plan `csvExport` entitlement (the pack bundles
 * the same Business-gated consent log, so it shares the gate). The gate here is display-only — the backend
 * enforces it (403) — so a non-entitled user sees a focusable, announced `<LockedFeature>` rather than a
 * dead `disabled` button.
 *
 * A confirmation dialog stands between the click and the download: the pack is an audit artifact bundling
 * personal data (the consent log), so we make the customer acknowledge what they're exporting before the
 * browser streams it. The download itself is a same-origin `<a download>` inside the dialog, so auth
 * cookies attach automatically and the backend streams the ZIP without a client-side navigation. A single
 * Sentry breadcrumb records that a pack was requested (feature tag only — never the site id or any PII).
 */
export function DownloadEvidencePackButton({ siteId }: { siteId: string }) {
  const t = useTranslations("analytics.evidence_pack");
  const entitlement = useEntitlement();
  const [open, setOpen] = useState(false);

  if (entitlement.isPending) {
    return (
      <Button variant="outline" aria-disabled="true" aria-busy="true">
        <FileArchiveIcon aria-hidden="true" />
        {t("label")}
      </Button>
    );
  }

  if (!entitlement.data?.limits.csvExport) {
    return <LockedFeature label={t("label")} reason={t("businessOnly")} />;
  }

  const handleDownload = () => {
    // Feature-tagged breadcrumb only — no site id, no PII (CLAUDE.md #4). The backend owns the audit trail
    // of who exported what; this is just operational signal that the surface is being used.
    Sentry.addBreadcrumb({
      category: "analytics.evidence_pack",
      message: "download",
      level: "info",
    });
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        render={
          <Button variant="outline">
            <FileArchiveIcon aria-hidden="true" />
            {t("label")}
          </Button>
        }
      />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("dialog.title")}</DialogTitle>
          <DialogDescription>{t("dialog.description")}</DialogDescription>
        </DialogHeader>
        <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">
          <li>{t("dialog.contents.policy")}</li>
          <li>{t("dialog.contents.consent")}</li>
          <li>{t("dialog.contents.scan")}</li>
          <li>{t("dialog.contents.manifest")}</li>
        </ul>
        <DialogFooter>
          <DialogClose render={<Button variant="ghost" />}>{t("dialog.cancel")}</DialogClose>
          {/* A real anchor styled as a button (not the Base UI Button primitive), so it keeps native
              link semantics and streams the download without a client-side navigation. */}
          <a
            href={evidencePackPath(siteId)}
            download
            onClick={handleDownload}
            className={cn(buttonVariants({ variant: "default" }))}
          >
            {t("dialog.confirm")}
          </a>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
