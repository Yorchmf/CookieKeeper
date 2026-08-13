import { Check } from "lucide-react";
import { useTranslations } from "next-intl";
import { Reveal } from "@/components/marketing/reveal";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

// Order + `popular` mirror the billing dashboard; facts trace to backend Plan.kt.
const PLANS = [
  { key: "starter", popular: false },
  { key: "pro", popular: true },
  { key: "business", popular: false },
] as const;

export function PricingSection() {
  const t = useTranslations("marketing.pricing");

  return (
    <section
      id="pricing"
      aria-labelledby="pricing-heading"
      className="mx-auto max-w-6xl scroll-mt-20 px-6 py-24"
    >
      <Reveal className="mx-auto mb-14 max-w-2xl text-center">
        <p className="text-xs font-medium tracking-[0.2em] text-brand uppercase">
          {t("eyebrow")}
        </p>
        <h2
          id="pricing-heading"
          className="mt-3 font-heading text-3xl font-semibold tracking-tight text-balance sm:text-4xl"
        >
          {t("title")}
        </h2>
        <p className="mt-4 text-pretty text-muted-foreground">{t("subtitle")}</p>
      </Reveal>

      <div className="grid items-start gap-6 lg:grid-cols-3">
        {PLANS.map((plan, index) => {
          const features = t.raw(`plans.${plan.key}.features`) as string[];
          return (
            <Reveal key={plan.key} delay={index * 80}>
              <article
                className={cn(
                  "relative flex h-full flex-col gap-6 rounded-2xl border p-8 transition-all",
                  plan.popular
                    ? "border-brand bg-card shadow-xl shadow-brand/10 lg:-translate-y-2"
                    : "border-border bg-card",
                )}
              >
                {plan.popular && (
                  <span className="absolute -top-3 left-8 inline-flex items-center rounded-full bg-brand px-3 py-1 text-xs font-semibold text-brand-foreground">
                    {t("mostPopular")}
                  </span>
                )}

                <div className="flex flex-col gap-1">
                  <h3 className="text-lg font-semibold tracking-tight">
                    {t(`plans.${plan.key}.name`)}
                  </h3>
                  <p className="text-sm text-muted-foreground">
                    {t(`plans.${plan.key}.tagline`)}
                  </p>
                </div>

                <p className="flex items-baseline gap-1">
                  <span className="font-heading text-4xl font-semibold tracking-tight">
                    {t(`plans.${plan.key}.price`)}
                  </span>
                  <span className="text-sm text-muted-foreground">
                    {t("perMonth")}
                  </span>
                </p>

                <Button
                  variant={plan.popular ? "brand" : "outline"}
                  size="lg"
                  className="w-full"
                  nativeButton={false}
                  render={<Link href="/signup" />}
                >
                  {t("cta")}
                </Button>

                <ul className="flex flex-col gap-3">
                  {features.map((feature) => (
                    <li key={feature} className="flex items-start gap-2.5 text-sm">
                      <Check className="mt-0.5 size-4 shrink-0 text-brand" />
                      <span className="text-pretty">{feature}</span>
                    </li>
                  ))}
                </ul>
              </article>
            </Reveal>
          );
        })}
      </div>
    </section>
  );
}
