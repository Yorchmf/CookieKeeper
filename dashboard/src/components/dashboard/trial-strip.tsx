"use client";

import { useNow, useTranslations } from "next-intl";

import { Link } from "@/i18n/navigation";
import { useEntitlement } from "@/hooks/use-billing";

const MS_PER_DAY = 86_400_000;

/**
 * Billing banner across the top of the dashboard home: the trial countdown while trialing, a prompt to
 * subscribe once the trial has ended, and nothing at all on a paid plan — a subscribed customer should
 * not be sold to on their own home page.
 *
 * Reads the SAME `useEntitlement()` query the billing page uses rather than duplicating billing state
 * into the overview payload, so both surfaces always agree and a plan change refreshes them together.
 * Renders nothing while loading or on error: this is context, and a failed strip must not push the
 * actual figures down the page or claim a state we could not confirm.
 */
export function TrialStrip() {
  const t = useTranslations("dashboard.trial");
  const now = useNow();
  const entitlement = useEntitlement();

  const data = entitlement.data;
  if (!data || data.state === "subscribed") return null;

  if (data.state === "expired") {
    return (
      <Strip
        text={t("expired")}
        cta={t("cta")}
      />
    );
  }

  const daysLeft =
    data.trialEndsAt != null
      ? Math.max(0, Math.ceil((new Date(data.trialEndsAt).getTime() - now.getTime()) / MS_PER_DAY))
      : null;
  if (daysLeft === null) return null;

  const cap = data.limits.consentEventCap;
  return (
    <Strip
      text={t("daysLeft", { days: daysLeft })}
      detail={
        cap != null
          ? t("usage", { used: data.consentEventsUsed ?? 0, max: cap })
          : undefined
      }
      cta={t("cta")}
    />
  );
}

function Strip({ text, detail, cta }: { text: string; detail?: string; cta: string }) {
  return (
    <aside className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-border bg-muted/40 px-4 py-3 text-sm">
      <span className="font-medium">{text}</span>
      {detail ? <span className="text-muted-foreground">{detail}</span> : null}
      <Link
        href="/billing"
        className="ml-auto font-medium underline underline-offset-4 hover:no-underline focus-visible:ring-3 focus-visible:ring-ring/50 rounded-sm outline-none"
      >
        {cta}
      </Link>
    </aside>
  );
}
