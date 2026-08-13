import { ChevronDown } from "lucide-react";
import { useTranslations } from "next-intl";
import { Reveal } from "@/components/marketing/reveal";

const ITEMS = [
  "compliant",
  "different",
  "analytics",
  "consentMode",
  "dataLocation",
  "cancel",
  "platforms",
  "freePlan",
] as const;

export function FaqSection() {
  const t = useTranslations("marketing.faq");

  return (
    <section
      id="faq"
      aria-labelledby="faq-heading"
      className="border-t border-border/60 bg-muted/30"
    >
      <div className="mx-auto max-w-3xl px-6 py-24">
        <Reveal className="mb-12 text-center">
          <p className="text-xs font-medium tracking-[0.2em] text-brand uppercase">
            {t("eyebrow")}
          </p>
          <h2
            id="faq-heading"
            className="mt-3 font-heading text-3xl font-semibold tracking-tight text-balance sm:text-4xl"
          >
            {t("title")}
          </h2>
          <p className="mt-4 text-pretty text-muted-foreground">
            {t("subtitle")}
          </p>
        </Reveal>

        <div className="flex flex-col gap-3">
          {ITEMS.map((item, index) => (
            <Reveal key={item} delay={index * 40}>
              <details className="group rounded-xl border border-border bg-card px-5 open:border-brand/40 open:bg-brand-subtle/20">
                <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-5 text-left font-medium [&::-webkit-details-marker]:hidden">
                  {t(`items.${item}.q`)}
                  <ChevronDown className="size-5 shrink-0 text-muted-foreground transition-transform duration-300 group-open:rotate-180" />
                </summary>
                <p className="pb-5 text-sm text-pretty text-muted-foreground">
                  {t(`items.${item}.a`)}
                </p>
              </details>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
