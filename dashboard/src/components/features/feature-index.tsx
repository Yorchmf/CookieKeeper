"use client";

import { useTranslations } from "next-intl";

import { useEntitlementGate } from "@/components/analytics/use-entitlement-gate";
import { Button } from "@/components/ui/button";
import { ButtonLink } from "@/components/ui/button-link";
import { EntitlementGateError } from "@/components/ui/entitlement-gate-error";
import { LockedFeature } from "@/components/ui/locked-feature";
import { useSites } from "@/hooks/use-sites";
import {
  FEATURE_GROUPS,
  featureHref,
  type FeatureEntry,
  type FeatureLock,
} from "@/lib/features/catalog";

/**
 * The feature index: every capability on the account, what it is for, and a link straight into using
 * it. Customers were finding features by accident, so this is the one page that admits they exist.
 *
 * It is deliberately both the onboarding surface and the upsell surface. A plan-locked capability is
 * **listed, not hidden** — rendered with the same `<LockedFeature>` affordance as everywhere else,
 * so the page never implies the product is smaller than it is. The gate comes from the shared
 * {@link useEntitlementGate}, which means a *failed* entitlement fetch shows a retry rather than
 * telling a paying customer to upgrade.
 *
 * Contents come from `FEATURE_GROUPS`; adding a capability is a row there plus its two strings.
 */
export function FeatureIndex() {
  const t = useTranslations("features");
  // Active sites only: a single archived site is not somewhere to send anyone.
  const sites = useSites("active");
  const siteCount = sites.data?.sites.length;
  // We can only deep-link a per-site feature when the choice is unambiguous. One active site — the
  // ordinary case — links straight in; zero or several fall back to the sites list, and the hint below
  // says which of the two happened rather than leaving the links looking broken.
  const siteId = siteCount === 1 ? (sites.data?.sites[0]?.id ?? null) : null;
  const hint =
    siteCount === 0 ? t("hint.noSites") : siteCount !== undefined && siteCount > 1 ? t("hint.manySites") : null;

  return (
    <main className="flex-1 p-6">
      <div className="flex max-w-5xl flex-col gap-10">
        <header className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t("title")}</h1>
          <p className="max-w-2xl text-sm text-muted-foreground">{t("subtitle")}</p>
          {hint ? <p className="max-w-2xl text-sm text-muted-foreground">{hint}</p> : null}
        </header>

        {FEATURE_GROUPS.map((group) => (
          <section key={group.key} aria-labelledby={`feature-group-${group.key}`} className="flex flex-col gap-4">
            <h2 id={`feature-group-${group.key}`} className="text-lg font-semibold tracking-tight">
              {t(`groups.${group.key}`)}
            </h2>
            <ul className="grid gap-4 sm:grid-cols-2">
              {group.features.map((feature) => (
                <FeatureCard key={feature.key} feature={feature} siteId={siteId} />
              ))}
            </ul>
          </section>
        ))}
      </div>
    </main>
  );
}

/**
 * Split on whether the entry is plan-gated at all, so the entitlement hook is only called by the cards
 * that actually need it — an unconditional call inside a shared card would break the rules of hooks the
 * moment the catalogue changes shape.
 */
function FeatureCard({ feature, siteId }: { feature: FeatureEntry; siteId: string | null }) {
  return feature.lock ? (
    <GatedCard feature={feature} lock={feature.lock} siteId={siteId} />
  ) : (
    <CardShell featureKey={feature.key} action={<OpenLink feature={feature} siteId={siteId} />} />
  );
}

/** A plan-gated card. Every state keeps the card itself — only the action changes. */
function GatedCard({
  feature,
  lock,
  siteId,
}: {
  feature: FeatureEntry;
  lock: FeatureLock;
  siteId: string | null;
}) {
  const t = useTranslations("features");
  const gate = useEntitlementGate((limits) => limits[lock.flag]);

  const action =
    gate.status === "pending" ? (
      <Button variant="outline" size="sm" aria-disabled="true" aria-busy="true">
        {t("open")}
      </Button>
    ) : gate.status === "error" ? (
      <EntitlementGateError label={t("open")} onRetry={gate.retry} size="sm" />
    ) : gate.status === "locked" ? (
      <LockedFeature label={t("open")} reason={t(`locked.${lock.plan}`)} size="sm" />
    ) : (
      <OpenLink feature={feature} siteId={siteId} />
    );

  return <CardShell featureKey={feature.key} action={action} />;
}

/**
 * The real, actionable link. The visible label stays the short "Open" so a page of twenty cards does
 * not read as twenty sentences, while `aria-label` names the feature — the visible text is a prefix of
 * the accessible name, which is what SC 2.5.3 (Label in Name) asks for, and it keeps a screen-reader
 * user's link list meaningful instead of twenty identical entries.
 *
 * A `ButtonLink` rather than `<Button render={<Link/>}>`: this navigates, so it must keep link
 * semantics. Base UI's button would put `role="button"` on the anchor and cost us the link role on
 * every card — see `ButtonLink` for the full reasoning.
 */
function OpenLink({ feature, siteId }: { feature: FeatureEntry; siteId: string | null }) {
  const t = useTranslations("features");

  return (
    <ButtonLink
      variant="outline"
      size="sm"
      href={featureHref(feature, siteId)}
      aria-label={t("openNamed", { feature: t(`items.${feature.key}.title`) })}
    >
      {t("open")}
    </ButtonLink>
  );
}

/**
 * One card: heading, what it is for, and whatever action its gate resolved to. The action sits in an
 * `mt-auto` row so cards in a grid row line their buttons up regardless of description length.
 */
function CardShell({ featureKey, action }: { featureKey: string; action: React.ReactNode }) {
  const t = useTranslations("features.items");

  return (
    <li className="flex flex-col gap-3 rounded-xl border border-border bg-card p-5">
      <div className="flex flex-col gap-1">
        <h3 className="font-medium">{t(`${featureKey}.title`)}</h3>
        <p className="text-sm text-muted-foreground">{t(`${featureKey}.description`)}</p>
      </div>
      <div className="mt-auto">{action}</div>
    </li>
  );
}
