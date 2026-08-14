"use client";

import { TriangleAlert } from "lucide-react";
import { useNow, useTranslations } from "next-intl";

import { Link } from "@/i18n/navigation";
import { useEntitlement } from "@/hooks/use-billing";
import { cn } from "@/lib/utils";

const MS_PER_DAY = 86_400_000;
// A trial with this many days or fewer left is "ending soon"; usage at or above this fraction of the
// cap is "near the limit". Either one escalates the strip's tone — a flat grey banner reads the same
// at 14 days as at 1, which is exactly when the upgrade nudge needs to land harder.
const TRIAL_ENDING_SOON_DAYS = 3;
const USAGE_URGENT_RATIO = 0.8;

/** The trial's consent-event usage against its cap, resolved for the bar. */
type Usage = { label: string; text: string; used: number; cap: number; urgent: boolean };

/**
 * Billing banner across the top of the dashboard home: the trial countdown while trialing, a prompt to
 * subscribe once the trial has ended, and nothing at all on a paid plan — a subscribed customer should
 * not be sold to on their own home page.
 *
 * Reads the SAME `useEntitlement()` query the billing page uses rather than duplicating billing state
 * into the overview payload, so both surfaces always agree and a plan change refreshes them together.
 * Renders nothing until there is a successful entitlement to show: this is context, and an unconfirmed
 * strip must not push the actual figures down the page or claim a state we could not resolve.
 *
 * The strip escalates to an urgent tone (destructive border/fill + a warning icon) when the trial is
 * nearly over or usage is near the cap. Colour is never the only cue — the day count, the "N of M"
 * figure, and the icon's shape all convey the urgency independently (SC 1.4.1), and the urgent *text*
 * stays at the normal foreground so it keeps a 4.5:1 contrast on the tint (SC 1.4.3).
 */
export function TrialStrip() {
  const t = useTranslations("dashboard.trial");
  const now = useNow();
  const entitlement = useEntitlement();

  const data = entitlement.data;
  if (!data || data.state === "subscribed") return null;

  if (data.state === "expired") {
    // Access to add sites/scans is frozen until they subscribe — the most urgent state, always emphasized.
    return <Strip urgent text={t("expired")} cta={t("cta")} />;
  }

  const daysLeft =
    data.trialEndsAt != null
      ? Math.max(0, Math.ceil((new Date(data.trialEndsAt).getTime() - now.getTime()) / MS_PER_DAY))
      : null;
  if (daysLeft === null) return null;

  const cap = data.limits.consentEventCap;
  const used = data.consentEventsUsed ?? 0;
  const nearCap = cap != null && cap > 0 && used / cap >= USAGE_URGENT_RATIO;
  const endingSoon = daysLeft <= TRIAL_ENDING_SOON_DAYS;

  return (
    <Strip
      urgent={endingSoon || nearCap}
      text={t("daysLeft", { days: daysLeft })}
      usage={
        cap != null
          ? { label: t("usageLabel"), text: t("usage", { used, max: cap }), used, cap, urgent: nearCap }
          : undefined
      }
      cta={t("cta")}
    />
  );
}

function Strip({
  text,
  usage,
  cta,
  urgent = false,
}: {
  text: string;
  usage?: Usage;
  cta: string;
  urgent?: boolean;
}) {
  return (
    // A `div`, not an `aside`: this is inline billing status, not a complementary landmark — an
    // unlabeled `complementary` region would only add rotor noise to screen-reader navigation.
    <div
      className={cn(
        "flex flex-col gap-2 rounded-xl border px-4 py-3 text-sm",
        urgent ? "border-destructive/50 bg-destructive/10" : "border-border bg-muted/40",
      )}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        {urgent ? (
          <TriangleAlert
            data-testid="trial-urgent-icon"
            aria-hidden="true"
            className="size-4 shrink-0 text-destructive"
          />
        ) : null}
        <span className="font-medium">{text}</span>
        <Link
          href="/billing"
          // Keep a real focus indicator in forced-colors mode: `ring-*` is a box-shadow, which Windows
          // High Contrast drops — so `outline-none` alone would leave no focus at all. A transparent
          // outline is invisible normally but gets promoted to a system colour when colours are forced.
          // `py-1 -my-1` lifts the hit target to the 24px minimum (SC 2.5.8) without changing layout.
          className="-my-1 ml-auto rounded-sm py-1 font-medium underline underline-offset-4 outline-none hover:no-underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-transparent focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          {cta}
        </Link>
      </div>
      {usage ? <UsageBar usage={usage} /> : null}
    </div>
  );
}

/**
 * Determinate meter for trial consent usage. The visible "N of M" figure is `aria-hidden` because the
 * meter already exposes it via `aria-valuetext` — assistive tech should hear it once, from the meter.
 * The fill animates `transform` (compositor-friendly), not `width`, per the project's motion rules, and
 * clamps at 100%: the cap never blocks recording (CLAUDE.md #3), so real usage can sit above it.
 */
function UsageBar({ usage }: { usage: Usage }) {
  const { label, text, used, cap, urgent } = usage;
  const percent = cap > 0 ? Math.min(100, Math.round((used / cap) * 100)) : 0;

  return (
    <div className="flex items-center gap-3">
      <div
        role="meter"
        data-tone={urgent ? "urgent" : "neutral"}
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={cap}
        aria-valuenow={Math.min(used, cap)}
        aria-valuetext={text}
        className="h-2 flex-1 overflow-hidden rounded-full bg-muted"
      >
        <div
          className={cn(
            // `motion-reduce:transition-none` honours prefers-reduced-motion; `forced-colors:bg-[Highlight]`
            // keeps the fill distinct from the track when High Contrast flattens both to system colours.
            "h-full w-full origin-left rounded-full transition-transform motion-reduce:transition-none forced-colors:bg-[Highlight]",
            urgent ? "bg-destructive" : "bg-primary",
          )}
          style={{ transform: `scaleX(${percent / 100})` }}
        />
      </div>
      <span aria-hidden="true" className="shrink-0 text-muted-foreground tabular-nums">
        {text}
      </span>
    </div>
  );
}
