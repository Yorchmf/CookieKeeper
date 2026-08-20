import { useTranslations } from "next-intl";
import { PublicScanWidget } from "@/components/public-scan/public-scan-widget";
import { buttonVariants } from "@/components/ui/button";
import { ButtonLink } from "@/components/ui/button-link";

export function HeroSection() {
  const t = useTranslations("marketing.hero");
  const tScan = useTranslations("marketing.scan");

  return (
    <section
      aria-labelledby="hero-heading"
      className="relative overflow-hidden border-b border-border/60"
    >
      {/* Brand atmosphere — a soft radial wash, purely decorative, no layout cost. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 -top-40 h-[32rem] bg-[radial-gradient(60%_60%_at_50%_0%,var(--brand-subtle),transparent_70%)] opacity-70 dark:opacity-40"
      />

      <div className="relative mx-auto flex max-w-5xl flex-col items-center gap-8 px-6 pt-20 pb-16 text-center sm:pt-28">
        <p className="inline-flex items-center gap-2 rounded-full border border-border bg-background/60 px-3 py-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">
          <span aria-hidden className="size-1.5 rounded-full bg-brand" />
          {t("euBadge")}
        </p>

        <h1
          id="hero-heading"
          className="max-w-3xl font-heading text-5xl font-semibold tracking-tight text-balance sm:text-6xl md:text-7xl"
        >
          {t("title")}
        </h1>

        <p className="max-w-2xl text-lg text-pretty text-muted-foreground sm:text-xl">
          {t("pitch")}
        </p>

        <div className="flex flex-col items-center gap-3 sm:flex-row">
          <ButtonLink variant="brand" size="lg" href="/signup">
            {t("cta")}
          </ButtonLink>
          {/* In-page anchor: a plain <a> so next-intl never locale-prefixes the fragment. */}
          <a href="#pricing" className={buttonVariants({ variant: "outline", size: "lg" })}>
            {t("ctaSecondary")}
          </a>
        </div>

        <p className="text-sm text-muted-foreground">{t("trust")}</p>
      </div>

      {/* The free scanner is the hero's interactive proof — the product on show. */}
      <div className="relative mx-auto w-full max-w-3xl px-6 pb-20">
        <div className="rounded-2xl border border-border bg-card p-6 shadow-xl shadow-black/5 sm:p-8 dark:shadow-black/40">
          <div className="mb-6 flex flex-col gap-2 text-center">
            <p className="text-xs font-medium tracking-[0.2em] text-brand uppercase">
              {tScan("eyebrow")}
            </p>
            <h2
              id="scan-widget-heading"
              className="text-2xl font-semibold tracking-tight text-balance sm:text-3xl"
            >
              {tScan("title")}
            </h2>
            <p className="mx-auto max-w-xl text-sm text-pretty text-muted-foreground">
              {tScan("subtitle")}
            </p>
          </div>
          <PublicScanWidget />
        </div>
      </div>
    </section>
  );
}
