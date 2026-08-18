"use client";

import type { VariantProps } from "class-variance-authority";
import { TriangleAlertIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId } from "react";

import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type EntitlementGateErrorProps = {
  /** The label the real control would carry, e.g. "Export CSV". */
  label: string;
  /** Re-run the entitlement query. */
  onRetry: () => void;
  /** Matches the sizing of the real control it stands in for. */
  size?: VariantProps<typeof buttonVariants>["size"];
  className?: string;
};

/**
 * Stand-in for a plan-gated control whose entitlement query *failed to load* — deliberately NOT the
 * upgrade prompt. The plan is unknown here, so telling the customer to "upgrade to Business" would be
 * wrong (and infuriating for someone who already pays) whenever the failure is just a transient blip.
 * We show a neutral "couldn't verify your plan" message plus a retry that refetches the entitlement.
 *
 * Mirrors {@link LockedFeature}'s a11y shape: a focusable `aria-disabled` placeholder (never the bare
 * `disabled` attribute, which drops out of the tab order) with a **visible**, `aria-describedby`-linked
 * reason. The click is swallowed so the placeholder can't act; only the retry link does anything.
 */
export function EntitlementGateError({
  label,
  onRetry,
  size = "default",
  className,
}: EntitlementGateErrorProps) {
  const t = useTranslations("common.entitlementError");
  const reasonId = useId();

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        aria-disabled="true"
        aria-describedby={reasonId}
        onClick={(event) => event.preventDefault()}
        className={cn(
          buttonVariants({ variant: "outline", size }),
          "cursor-not-allowed text-muted-foreground hover:text-muted-foreground",
          className,
        )}
      >
        <TriangleAlertIcon aria-hidden="true" />
        {label}
      </button>
      <span id={reasonId} className="text-xs text-muted-foreground">
        {t("reason")}{" "}
        <button
          type="button"
          onClick={onRetry}
          className="rounded-sm font-medium text-foreground underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          {t("retry")}
        </button>
      </span>
    </div>
  );
}
