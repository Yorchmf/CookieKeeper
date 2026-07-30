import { setRequestLocale } from "next-intl/server";
import { useTranslations } from "next-intl";
import { use } from "react";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";

export default function LandingPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = use(props.params);
  setRequestLocale(locale);

  const t = useTranslations("marketing.hero");

  return (
    <main className="flex flex-1 items-center justify-center px-6">
      <section
        aria-labelledby="hero-heading"
        className="flex max-w-2xl flex-col items-center gap-6 py-24 text-center"
      >
        <p className="rounded-full border border-border px-3 py-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">
          {t("euBadge")}
        </p>
        <h1
          id="hero-heading"
          className="text-5xl font-semibold tracking-tight text-balance sm:text-6xl"
        >
          {t("title")}
        </h1>
        <p className="max-w-prose text-lg text-pretty text-muted-foreground">
          {t("pitch")}
        </p>
        <Button size="lg" nativeButton={false} render={<Link href="/dashboard" />}>
          {t("cta")}
        </Button>
      </section>
    </main>
  );
}
