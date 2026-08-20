"use client";

import { CopyIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";

import { FormError } from "@/components/forms/form-error";
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
import { useCopyBannerConfig } from "@/hooks/use-banner";
import { useSites } from "@/hooks/use-sites";
import { getApiErrorCode, type ErrorMessageCode } from "@/lib/api-error-codes";
import { MAX_BANNER_COPY_TARGETS } from "@/lib/api/banner";

/**
 * Apply this site's banner to the account's other sites, so a multi-site customer sets colours,
 * position, and five languages of wording once instead of per site.
 *
 * Only the *source* site is identified to the API — the document itself is read server-side and
 * published as a new version on each target, which is why nothing here reconstructs or uploads a
 * config. The copy is all-or-nothing: if any target is rejected, no target changed, so the failure
 * message needs no "some of them worked" caveat.
 *
 * Renders nothing when the account has no other active site: on Starter and on trial the plan allows
 * exactly one, and an always-visible control that can never do anything is worse than its absence.
 */
export function CopyBannerCard({ siteId }: { siteId: string }) {
  const t = useTranslations("banner.copy");
  const tErrors = useTranslations("auth.errors");
  const sites = useSites("active");
  const copyBanner = useCopyBannerConfig(siteId);

  const [isOpen, setIsOpen] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [errorCode, setErrorCode] = useState<ErrorMessageCode | null>(null);

  const targets = (sites.data?.sites ?? []).filter((site) => site.id !== siteId);
  if (targets.length === 0) {
    return null;
  }

  const isOverCap = selectedIds.length > MAX_BANNER_COPY_TARGETS;

  const toggle = (id: string) => {
    setSelectedIds((previous) =>
      previous.includes(id)
        ? previous.filter((selected) => selected !== id)
        : [...previous, id],
    );
  };

  const closeAndReset = () => {
    setIsOpen(false);
    setSelectedIds([]);
    setErrorCode(null);
  };

  const handleOpenChange = (open: boolean) => {
    if (open) {
      setIsOpen(true);
      return;
    }
    // Stay open while the copy is in flight: closing would hide both the pending state and any error,
    // leaving the customer unsure whether their other sites were changed.
    if (copyBanner.isPending) {
      return;
    }
    closeAndReset();
  };

  const handleApply = async () => {
    setErrorCode(null);
    try {
      const result = await copyBanner.mutateAsync(selectedIds);
      // Report the server's own list, not the selection — it is the authoritative record of what changed.
      toast.success(t("applied", { count: result.copiedToSiteIds.length }));
      closeAndReset();
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <Dialog open={isOpen} onOpenChange={handleOpenChange}>
          <DialogTrigger
            render={
              <Button type="button" variant="outline">
                <CopyIcon aria-hidden="true" className="mr-2 size-4" />
                {t("cta")}
              </Button>
            }
          />
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t("dialogTitle")}</DialogTitle>
              <DialogDescription>{t("dialogDescription")}</DialogDescription>
            </DialogHeader>

            <FormError message={errorCode ? tErrors(errorCode) : null} />

            <fieldset className="flex max-h-64 flex-col gap-1 overflow-y-auto">
              <legend className="sr-only">{t("legend")}</legend>
              {targets.map((site) => (
                <label
                  key={site.id}
                  className="flex cursor-pointer items-center gap-3 rounded-md px-2 py-2 text-sm transition-colors hover:bg-muted has-checked:bg-muted/60"
                >
                  <input
                    type="checkbox"
                    className="size-4 shrink-0 accent-primary"
                    checked={selectedIds.includes(site.id)}
                    onChange={() => toggle(site.id)}
                  />
                  <span className="truncate">{site.domain}</span>
                </label>
              ))}
            </fieldset>

            {isOverCap && (
              <p role="alert" className="text-sm text-destructive">
                {t("tooMany", { max: MAX_BANNER_COPY_TARGETS })}
              </p>
            )}

            <DialogFooter>
              <Button
                type="button"
                variant="ghost"
                onClick={() => handleOpenChange(false)}
                disabled={copyBanner.isPending}
              >
                {t("cancel")}
              </Button>
              <Button
                type="button"
                onClick={() => void handleApply()}
                disabled={
                  copyBanner.isPending || selectedIds.length === 0 || isOverCap
                }
              >
                {copyBanner.isPending
                  ? t("applying")
                  : t("apply", { count: selectedIds.length })}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </CardContent>
    </Card>
  );
}
