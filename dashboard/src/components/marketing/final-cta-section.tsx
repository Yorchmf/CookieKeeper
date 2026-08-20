import { useTranslations } from "next-intl";
import { Reveal } from "@/components/marketing/reveal";
import { buttonVariants } from "@/components/ui/button";
import { ButtonLink } from "@/components/ui/button-link";

export function FinalCtaSection() {
  const t = useTranslations("marketing.finalCta");

  return (
    <section aria-labelledby="cta-heading" className="mx-auto max-w-6xl px-6 py-24">
      <Reveal className="relative overflow-hidden rounded-3xl border border-brand/30 bg-brand-subtle/40 px-6 py-16 text-center sm:px-16">
        <div
          aria-hidden
          className="pointer-events-none absolute inset-x-0 -bottom-32 h-64 bg-[radial-gradient(50%_100%_at_50%_100%,var(--brand-subtle),transparent_70%)]"
        />
        <div className="relative flex flex-col items-center gap-6">
          <h2
            id="cta-heading"
            className="max-w-2xl font-heading text-3xl font-semibold tracking-tight text-balance sm:text-4xl"
          >
            {t("title")}
          </h2>
          <p className="max-w-xl text-pretty text-muted-foreground">
            {t("subtitle")}
          </p>
          <div className="flex flex-col items-center gap-3 sm:flex-row">
            <ButtonLink variant="brand" size="lg" href="/signup">
              {t("cta")}
            </ButtonLink>
            {/* In-page anchor: a plain <a> so next-intl never locale-prefixes the fragment. */}
            <a href="#hero-heading" className={buttonVariants({ variant: "outline", size: "lg" })}>
              {t("ctaSecondary")}
            </a>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
