import type { Metadata } from "next";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { use } from "react";
import { FaqSection } from "@/components/marketing/faq-section";
import { FinalCtaSection } from "@/components/marketing/final-cta-section";
import { HeroSection } from "@/components/marketing/hero-section";
import { HowItWorksSection } from "@/components/marketing/how-it-works-section";
import { PricingSection } from "@/components/marketing/pricing-section";
import { ProblemSection } from "@/components/marketing/problem-section";
import { StructuredData } from "@/components/marketing/structured-data";
import { localeAlternates } from "@/lib/site";

export async function generateMetadata(props: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await props.params;
  const [app, t] = await Promise.all([
    getTranslations({ locale, namespace: "app" }),
    getTranslations({ locale, namespace: "marketing" }),
  ]);

  const title = app("tagline");
  const description = t("hero.pitch");

  return {
    title,
    description,
    alternates: localeAlternates(locale),
    openGraph: {
      type: "website",
      siteName: app("name"),
      title: `${app("name")} — ${title}`,
      description,
      locale,
      url: localeAlternates(locale).canonical,
    },
    twitter: {
      card: "summary_large_image",
      title: `${app("name")} — ${title}`,
      description,
    },
  };
}

export default function LandingPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = use(props.params);
  setRequestLocale(locale);

  return (
    <main className="flex flex-col">
      <StructuredData locale={locale} />
      <HeroSection />
      <ProblemSection />
      <HowItWorksSection />
      <PricingSection />
      <FaqSection />
      <FinalCtaSection />
    </main>
  );
}
