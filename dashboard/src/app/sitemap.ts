import type { MetadataRoute } from "next";
import { routing } from "@/i18n/routing";
import { localeUrl } from "@/lib/site";

/**
 * Sitemap for the public marketing surface. Only indexable, unauthenticated
 * pages belong here — the dashboard, auth flows and hosted-policy pages are
 * excluded (auth-gated or per-customer, not for search). Each entry carries
 * `hreflang` alternates so search engines pair the localized versions.
 */
export default function sitemap(): MetadataRoute.Sitemap {
  const languages: Record<string, string> = {};
  for (const locale of routing.locales) {
    languages[locale] = localeUrl(locale);
  }

  return routing.locales.map((locale) => ({
    url: localeUrl(locale),
    changeFrequency: "weekly",
    priority: locale === routing.defaultLocale ? 1 : 0.8,
    alternates: { languages },
  }));
}
