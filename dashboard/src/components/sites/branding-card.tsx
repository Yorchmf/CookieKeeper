"use client";

import { useTranslations } from "next-intl";
import { useId } from "react";
import { toast } from "sonner";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useSetSiteBranding } from "@/hooks/use-sites";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { cn } from "@/lib/utils";

type BrandingCardProps = {
  siteId: string;
  /** The customer's saved preference to hide the credit. */
  hideBranding: boolean;
  /** Whether the plan actually grants branding removal — gates the control. */
  isEntitled: boolean;
};

/**
 * The per-site "hide the Powered by Complyr credit" control. The switch reflects the *stored*
 * preference regardless of plan; it is only operable when the plan grants removal (`isEntitled`).
 * When locked it stays focusable and announces the reason (mirrors {@link LockedFeature}'s WCAG 2.2
 * rationale) rather than using the bare `disabled` attribute, so keyboard/AT users learn *why* and
 * reach the upgrade link. The gate here is display-only — the backend floors the effective branding
 * against the entitlement, so a locked toggle can never grant the paid feature.
 */
export function BrandingCard({
  siteId,
  hideBranding,
  isEntitled,
}: BrandingCardProps) {
  const t = useTranslations("sites.detail.branding");
  const tLocked = useTranslations("common.lockedFeature");
  const tErrors = useTranslations("auth.errors");
  const setBranding = useSetSiteBranding(siteId);
  const labelId = useId();
  const reasonId = useId();

  const isLocked = !isEntitled;
  const isInteractive = isEntitled && !setBranding.isPending;

  const handleToggle = async () => {
    try {
      await setBranding.mutateAsync(!hideBranding);
    } catch (error) {
      toast.error(tErrors(getApiErrorCode(error)));
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-center justify-between gap-4">
          <span id={labelId} className="text-sm font-medium">
            {t("toggleLabel")}
          </span>
          <button
            type="button"
            role="switch"
            aria-checked={hideBranding}
            aria-labelledby={labelId}
            aria-describedby={isLocked ? reasonId : undefined}
            aria-disabled={!isInteractive}
            // Swallow both pointer and keyboard-dispatched clicks when the control isn't interactive,
            // so a locked or in-flight toggle can never act while staying focusable and announced.
            onClick={(event) => {
              if (!isInteractive) {
                event.preventDefault();
                return;
              }
              void handleToggle();
            }}
            className={cn(
              "relative inline-flex h-6 w-11 shrink-0 items-center rounded-full outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background",
              hideBranding ? "bg-primary" : "bg-input",
              !isInteractive && "cursor-not-allowed opacity-60",
            )}
          >
            <span
              className={cn(
                "inline-block h-5 w-5 transform rounded-full bg-background shadow transition-transform",
                hideBranding ? "translate-x-5" : "translate-x-0.5",
              )}
            />
          </button>
        </div>
        {isLocked ? (
          <p id={reasonId} className="text-xs text-muted-foreground">
            {t("lockedHint")}{" "}
            <Link
              href="/billing"
              className="rounded-sm font-medium text-foreground underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            >
              {tLocked("upgrade")}
            </Link>
          </p>
        ) : (
          <p className="text-xs text-muted-foreground">
            {hideBranding ? t("hiddenHint") : t("shownHint")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
