"use client";

import { useFormatter, useNow, useTranslations } from "next-intl";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useCheckout, useEntitlement, usePortal } from "@/hooks/use-billing";
import type { BillingState, Entitlement, PlanId } from "@/lib/api/billing";

/** The purchasable plans in display order; PRO is the highlighted default. */
const PLANS: ReadonlyArray<{ id: PlanId; popular: boolean }> = [
  { id: "STARTER", popular: false },
  { id: "PRO", popular: true },
  { id: "BUSINESS", popular: false },
];

const MS_PER_DAY = 86_400_000;

/** Badge tone per billing state — subscribed reads as success, expired as a warning. */
const STATE_VARIANT: Record<BillingState, "default" | "secondary" | "destructive"> = {
  subscribed: "default",
  trial: "secondary",
  expired: "destructive",
};

/** Dashboard billing surface: current state + usage, a manage-subscription link, and the plan grid. */
export function BillingManager() {
  const t = useTranslations("billing");
  const entitlement = useEntitlement();

  if (entitlement.isPending) {
    return (
      <main className="flex-1 p-6" aria-busy="true">
        <div className="flex max-w-4xl flex-col gap-4">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-72 w-full" />
        </div>
      </main>
    );
  }

  if (entitlement.isError || !entitlement.data) {
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
        aria-labelledby="billing-heading"
        className="flex max-w-4xl flex-col gap-6"
      >
        <header className="flex flex-col gap-1">
          <h1 id="billing-heading" className="text-2xl font-semibold tracking-tight">
            {t("title")}
          </h1>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </header>

        <CurrentPlanCard entitlement={entitlement.data} />
        <PlansGrid entitlement={entitlement.data} />
      </section>
    </main>
  );
}

/** State badge, trial countdown or site usage, and (when subscribed) the Stripe portal link. */
function CurrentPlanCard({ entitlement }: { entitlement: Entitlement }) {
  const t = useTranslations("billing");
  const format = useFormatter();
  // Stable "now" from next-intl (not Date.now(), which is impure in render).
  const now = useNow();
  const portal = usePortal();

  const openPortal = async () => {
    try {
      await portal.mutateAsync();
    } catch {
      toast.error(t("manage.error"));
    }
  };

  const trialDaysLeft =
    entitlement.trialEndsAt !== null
      ? Math.max(
          0,
          Math.ceil((new Date(entitlement.trialEndsAt).getTime() - now.getTime()) / MS_PER_DAY),
        )
      : null;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-3">
          <CardTitle>{t("current.title")}</CardTitle>
          <Badge variant={STATE_VARIANT[entitlement.state]}>
            {t(`state.${entitlement.state}`)}
          </Badge>
        </div>
        <CardDescription>
          {entitlement.state === "trial" && trialDaysLeft !== null
            ? t("trial.daysLeft", { days: trialDaysLeft })
            : entitlement.state === "subscribed" && entitlement.plan
              ? t("current.onPlan", { plan: t(`plans.${entitlement.plan.toLowerCase()}.name`) })
              : t("expired.description")}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-1">
          <span className="text-sm font-medium">{t("usage.sitesLabel")}</span>
          <span className="text-sm text-muted-foreground">
            {t("usage.sites", {
              used: entitlement.activeSites,
              max: entitlement.limits.maxSites,
            })}
          </span>
        </div>
        {entitlement.state === "trial" && entitlement.trialEndsAt ? (
          <p className="text-sm text-muted-foreground">
            {t("trial.endsOn", {
              date: format.dateTime(new Date(entitlement.trialEndsAt), {
                dateStyle: "medium",
              }),
            })}
          </p>
        ) : null}
        {entitlement.state === "subscribed" ? (
          <div>
            <Button
              variant="outline"
              onClick={() => void openPortal()}
              disabled={portal.isPending}
            >
              {t("manage.button")}
            </Button>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** The three plan cards; the account's active plan is marked current, the rest offer Checkout. */
function PlansGrid({ entitlement }: { entitlement: Entitlement }) {
  const t = useTranslations("billing");
  const checkout = useCheckout();

  const choosePlan = async (plan: PlanId) => {
    try {
      await checkout.mutateAsync(plan);
    } catch {
      toast.error(t("checkout.error"));
    }
  };

  return (
    <section aria-labelledby="plans-heading" className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id="plans-heading" className="text-lg font-semibold tracking-tight">
          {t("plans.title")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("plans.description")}</p>
      </div>
      <div className="grid gap-4 md:grid-cols-3">
        {PLANS.map(({ id, popular }) => (
          <PlanCard
            key={id}
            planId={id}
            popular={popular}
            isCurrent={entitlement.state === "subscribed" && entitlement.plan === id}
            pendingPlan={checkout.isPending ? (checkout.variables ?? null) : null}
            onChoose={() => void choosePlan(id)}
          />
        ))}
      </div>
    </section>
  );
}

/** A single plan: name, price, feature bullets, and the Checkout / current-plan action. */
function PlanCard({
  planId,
  popular,
  isCurrent,
  pendingPlan,
  onChoose,
}: {
  planId: PlanId;
  popular: boolean;
  isCurrent: boolean;
  pendingPlan: PlanId | null;
  onChoose: () => void;
}) {
  const t = useTranslations("billing");
  const key = planId.toLowerCase();
  const isPending = pendingPlan === planId;
  // Per-plan bullet list, authored per locale. t.raw is untyped; guard against a locale that ever
  // drops the array so a missing catalog entry degrades to no bullets instead of crashing the page.
  const rawFeatures = t.raw(`plans.${key}.features`);
  const features: string[] = Array.isArray(rawFeatures)
    ? rawFeatures.filter((item): item is string => typeof item === "string")
    : [];

  return (
    <Card className={popular ? "border-primary" : undefined}>
      <CardHeader>
        <div className="flex items-center gap-2">
          <CardTitle>{t(`plans.${key}.name`)}</CardTitle>
          {popular ? <Badge>{t("plans.popular")}</Badge> : null}
        </div>
        <CardDescription>
          <span className="text-2xl font-semibold text-foreground">
            {t(`plans.${key}.price`)}
          </span>{" "}
          {t("plans.perMonth")}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-1 flex-col gap-4">
        <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
          {features.map((feature) => (
            <li key={feature}>{feature}</li>
          ))}
        </ul>
        <Button
          className="mt-auto w-full"
          variant={popular ? "default" : "outline"}
          disabled={isCurrent || pendingPlan !== null}
          onClick={onChoose}
        >
          {isCurrent
            ? t("plans.current")
            : isPending
              ? t("plans.redirecting")
              : t("plans.choose", { plan: t(`plans.${key}.name`) })}
        </Button>
      </CardContent>
    </Card>
  );
}
