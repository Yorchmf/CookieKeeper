"use client";

import { CheckIcon, GlobeIcon, PaletteIcon, ScanSearchIcon, ShieldCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";

import { Link } from "@/i18n/navigation";
import type { OnboardingProgress } from "@/lib/api/overview";
import { cn } from "@/lib/utils";

/**
 * The four getting-started steps, in the order a customer walks them. The tuple order IS the display and
 * completion order — the first incomplete step drives the primary CTA — so it must match the funnel.
 *
 * Every step routes to `/sites`: onboarding is account-level, the sites list is the hub the whole flow
 * starts from, and once a site exists its own page takes over. `flag` names the field on
 * [OnboardingProgress] that marks the step done, keeping this table the single place the mapping lives.
 */
const STEPS = [
  { key: "addSite", flag: "addedSite", Icon: GlobeIcon },
  { key: "scan", flag: "scanned", Icon: ScanSearchIcon },
  { key: "customise", flag: "customisedBanner", Icon: PaletteIcon },
  { key: "verify", flag: "verified", Icon: ShieldCheckIcon },
] as const satisfies ReadonlyArray<{
  key: string;
  flag: keyof OnboardingProgress;
  Icon: typeof GlobeIcon;
}>;

const STEP_HREF = "/sites";

/**
 * First-run getting-started checklist for the dashboard home. Presentational: it renders whatever
 * [OnboardingProgress] it is handed and derives everything else (count, the current step) during render —
 * the parent decides whether to show it at all (it hides once every step is done).
 *
 * Each step is a status row, not a link; a single primary CTA points at the first incomplete step so there
 * is one obvious next action rather than four competing ones.
 */
export function OnboardingChecklist({ progress }: { progress: OnboardingProgress }) {
  const t = useTranslations("dashboard.onboarding");

  const doneCount = STEPS.filter((step) => progress[step.flag]).length;
  // The first step not yet done — the one the customer should do next. Undefined only when everything is
  // complete, which is exactly when the parent stops rendering this component, so the CTA always resolves.
  const nextStep = STEPS.find((step) => !progress[step.flag]);

  return (
    <section
      aria-labelledby="onboarding-heading"
      className="flex flex-col gap-5 rounded-xl border border-border bg-card p-6"
    >
      <header className="flex flex-col gap-3">
        <div className="flex flex-col gap-1">
          <h2 id="onboarding-heading" className="text-lg font-semibold tracking-tight">
            {t("title")}
          </h2>
          <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
        </div>
        <ProgressBar
          done={doneCount}
          total={STEPS.length}
          name={t("progressLabel")}
          label={t("progress", { done: doneCount, total: STEPS.length })}
        />
      </header>

      <ol className="flex flex-col gap-2">
        {STEPS.map((step) => (
          <StepRow
            key={step.key}
            Icon={step.Icon}
            title={t(`steps.${step.key}.title`)}
            description={t(`steps.${step.key}.description`)}
            done={progress[step.flag]}
            current={step.key === nextStep?.key}
            doneLabel={t("doneLabel")}
            currentLabel={t("currentLabel")}
          />
        ))}
      </ol>

      {nextStep ? (
        <Link
          href={STEP_HREF}
          className="self-start rounded-md border border-transparent bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 outline-none forced-colors:outline forced-colors:outline-2"
        >
          {t(`steps.${nextStep.key}.cta`)}
        </Link>
      ) : null}
    </section>
  );
}

/**
 * A determinate progress bar. `name` is the static accessible name ("what is progressing"); `label`
 * humanises the value ("N of M done") via `aria-valuetext`. The visible copy of `label` is `aria-hidden`
 * so assistive tech hears the value once, from the progressbar, not twice. The fill animates `transform`
 * (compositor-friendly), not `width`, per the project's motion rules.
 */
function ProgressBar({
  done,
  total,
  name,
  label,
}: {
  done: number;
  total: number;
  name: string;
  label: string;
}) {
  const percent = total === 0 ? 0 : Math.round((done / total) * 100);

  return (
    <div className="flex items-center gap-3">
      <div
        role="progressbar"
        aria-label={name}
        aria-valuemin={0}
        aria-valuemax={total}
        aria-valuenow={done}
        aria-valuetext={label}
        className="h-2 flex-1 overflow-hidden rounded-full bg-muted"
      >
        <div
          className="h-full origin-left rounded-full bg-primary transition-transform duration-500"
          style={{ transform: `scaleX(${percent / 100})` }}
        />
      </div>
      <span aria-hidden="true" className="shrink-0 text-sm font-medium text-muted-foreground tabular-nums">
        {label}
      </span>
    </div>
  );
}

/**
 * One step. Three visual states, in falling emphasis: done (filled check, muted title), current (accented
 * marker + ring, the one the customer acts on next), and upcoming (plain outline). The check-vs-icon swap
 * and the `doneLabel` sr-only text mean the state is conveyed to assistive tech, not by colour alone.
 */
function StepRow({
  Icon,
  title,
  description,
  done,
  current,
  doneLabel,
  currentLabel,
}: {
  Icon: typeof GlobeIcon;
  title: string;
  description: string;
  done: boolean;
  current: boolean;
  doneLabel: string;
  currentLabel: string;
}) {
  return (
    <li
      // `aria-current` gives assistive tech the "you are here" step programmatically — the ring/tint alone
      // conveys it by colour, which SC 1.4.1 does not accept as the only cue.
      aria-current={current ? "step" : undefined}
      className={cn(
        "flex items-start gap-3 rounded-lg border px-4 py-3 transition-colors",
        done && "border-border/60 bg-transparent",
        current && "border-ring bg-muted/40",
        !done && !current && "border-border bg-transparent",
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full border",
          done && "border-primary bg-primary text-primary-foreground",
          current && "border-ring text-foreground",
          !done && !current && "border-border text-muted-foreground",
        )}
      >
        {done ? <CheckIcon className="size-3.5" /> : <Icon className="size-3.5" />}
      </span>
      <span className="min-w-0 flex-1">
        <span className={cn("block font-medium", done && "text-muted-foreground")}>
          {title}
          {done ? <span className="sr-only"> — {doneLabel}</span> : null}
          {current ? <span className="sr-only"> — {currentLabel}</span> : null}
        </span>
        <span className="block text-sm text-muted-foreground">{description}</span>
      </span>
    </li>
  );
}
