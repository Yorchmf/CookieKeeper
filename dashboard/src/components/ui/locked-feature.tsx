"use client";

import type { VariantProps } from "class-variance-authority";
import { LockIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useId } from "react";

import { buttonVariants } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

type LockedFeatureProps = {
  /** The label the unlocked control would carry, e.g. "Re-scan now". */
  label: string;
  /** Plain-language reason the feature is locked — read by everyone, associated for AT via describedby. */
  reason: string;
  /** Where the upgrade link points. Defaults to the billing page. */
  upgradeHref?: string;
  /** Matches the sizing of the real control it stands in for. */
  size?: VariantProps<typeof buttonVariants>["size"];
  className?: string;
};

/**
 * A plan-locked control rendered as a **focusable, announced** placeholder — never the bare `disabled`
 * attribute. Why not `disabled`:
 *  - `disabled` drops the control out of the tab order, so a keyboard/screen-reader user can never land
 *    on it and therefore never learns *why* it's unavailable — the upsell becomes mouse-only.
 *  - Disabled elements emit no focus/pointer events, so a hover/focus explanation can't fire — WCAG 2.2
 *    1.4.13 is unsatisfiable by construction.
 * Instead we use `aria-disabled` (focusable, announced as unavailable per the ARIA APG) plus
 * `aria-describedby` pointing at a **visible** reason (3.3.2 for AT users, not just sighted ones). The
 * surface is dimmed via a muted background and dashed border — the reason text keeps full 4.5:1 contrast
 * (1.4.3) — and `focus-visible` styling stays on (2.4.11 / 2.4.13). The click is swallowed so neither a
 * pointer nor an Enter/Space keypress can act on a locked control; the gate here is display-only, the
 * backend enforces the real 403.
 */
export function LockedFeature({
  label,
  reason,
  upgradeHref = "/billing",
  size = "default",
  className,
}: LockedFeatureProps) {
  const t = useTranslations("common.lockedFeature");
  const reasonId = useId();

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        aria-disabled="true"
        aria-describedby={reasonId}
        // Swallow both pointer clicks and keyboard-dispatched clicks (Enter/Space on a button fire a
        // click) so a locked control can never trigger an action, while staying focusable.
        onClick={(event) => event.preventDefault()}
        className={cn(
          buttonVariants({ variant: "outline", size }),
          "cursor-not-allowed border-dashed bg-muted/50 text-muted-foreground hover:bg-muted/50 hover:text-muted-foreground",
          className,
        )}
      >
        <LockIcon aria-hidden="true" />
        {label}
      </button>
      <span id={reasonId} className="text-xs text-muted-foreground">
        {reason}{" "}
        <Link
          href={upgradeHref}
          className="rounded-sm font-medium text-foreground underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          {t("upgrade")}
        </Link>
      </span>
    </div>
  );
}
