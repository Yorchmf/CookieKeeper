import { useTranslations } from "next-intl";
import { Reveal } from "@/components/marketing/reveal";
import { SnippetCopy } from "@/components/marketing/snippet-copy";

const STEPS = ["scan", "customise", "embed"] as const;

// The real snippet shape (SiteService.embedSnippet) with a placeholder key.
const DEMO_SNIPPET =
  '<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_your_site_key"></script>';

export function HowItWorksSection() {
  const t = useTranslations("marketing.how");

  return (
    <section
      aria-labelledby="how-heading"
      className="border-y border-border/60 bg-muted/30"
    >
      <div className="mx-auto max-w-6xl px-6 py-24">
        <Reveal className="mx-auto mb-14 max-w-2xl text-center">
          <p className="text-xs font-medium tracking-[0.2em] text-brand uppercase">
            {t("eyebrow")}
          </p>
          <h2
            id="how-heading"
            className="mt-3 font-heading text-3xl font-semibold tracking-tight text-balance sm:text-4xl"
          >
            {t("title")}
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            {t("subtitle")}
          </p>
        </Reveal>

        <ol className="grid gap-6 md:grid-cols-3">
          {STEPS.map((step, index) => (
            <Reveal as="li" key={step} delay={index * 80}>
              <div className="flex h-full flex-col gap-3 rounded-xl border border-border bg-card p-6">
                <span className="font-heading text-4xl font-semibold text-brand/70">
                  {t(`steps.${step}.step`)}
                </span>
                <h3 className="text-lg font-semibold tracking-tight">
                  {t(`steps.${step}.title`)}
                </h3>
                <p className="text-sm text-pretty text-muted-foreground">
                  {t(`steps.${step}.body`)}
                </p>
              </div>
            </Reveal>
          ))}
        </ol>

        <Reveal
          delay={160}
          className="mx-auto mt-8 max-w-2xl rounded-xl border border-border bg-card p-6"
        >
          <SnippetCopy code={DEMO_SNIPPET} />
        </Reveal>
      </div>
    </section>
  );
}
