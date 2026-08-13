import { getTranslations } from "next-intl/server";
import { SITE_URL, localeUrl } from "@/lib/site";

// FAQ entries mirror faq-section.tsx; kept in sync so the rich result matches
// what the page actually renders (a Google requirement for FAQPage markup).
const FAQ_ITEMS = [
  "compliant",
  "different",
  "analytics",
  "consentMode",
  "dataLocation",
  "cancel",
  "platforms",
  "freePlan",
] as const;

/**
 * JSON-LD structured data for the landing page: Organization + SoftwareApplication
 * + FAQPage. Emitted server-side so crawlers get it in the initial HTML. Copy is
 * pulled from the message catalog so every locale ships translated markup.
 */
export async function StructuredData({ locale }: { locale: string }) {
  const [app, t, faq] = await Promise.all([
    getTranslations({ locale, namespace: "app" }),
    getTranslations({ locale, namespace: "marketing" }),
    getTranslations({ locale, namespace: "marketing.faq" }),
  ]);

  const organization = {
    "@context": "https://schema.org",
    "@type": "Organization",
    name: app("name"),
    url: SITE_URL,
    logo: `${SITE_URL}/icon.svg`,
    description: app("tagline"),
  };

  const softwareApplication = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: app("name"),
    applicationCategory: "BusinessApplication",
    operatingSystem: "Web",
    url: localeUrl(locale),
    description: t("hero.pitch"),
    offers: {
      "@type": "Offer",
      price: "9",
      priceCurrency: "EUR",
    },
  };

  const faqPage = {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    mainEntity: FAQ_ITEMS.map((item) => ({
      "@type": "Question",
      name: faq(`items.${item}.q`),
      acceptedAnswer: {
        "@type": "Answer",
        text: faq(`items.${item}.a`),
      },
    })),
  };

  const graph = [organization, softwareApplication, faqPage];

  return (
    <script
      type="application/ld+json"
      // JSON.stringify output is safe: values come from our own catalog, and
      // stringify escapes the payload. This is the documented Next.js pattern.
      dangerouslySetInnerHTML={{ __html: JSON.stringify(graph) }}
    />
  );
}
