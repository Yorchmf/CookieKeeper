import { routing } from "@/i18n/routing";

/**
 * Canonical public origin for the marketing site, used to build absolute URLs
 * for metadata (`metadataBase`), `hreflang` alternates, JSON-LD, the sitemap
 * and robots. Env-overridable so dev/prd point at their own domains; the apex
 * `complyr.eu` is the production default (the widget CDN lives at
 * `cdn.complyr.eu`, the site at the apex).
 */
export const SITE_URL = (
  process.env.NEXT_PUBLIC_SITE_URL ?? "https://complyr.eu"
).replace(/\/$/, "");

/** Absolute URL for a locale-scoped path (leading slash optional). */
export function localeUrl(locale: string, path = ""): string {
  const suffix = path && !path.startsWith("/") ? `/${path}` : path;
  return `${SITE_URL}/${locale}${suffix}`;
}

/**
 * `alternates` block for a locale-scoped page: a self-referencing canonical
 * plus one `hreflang` entry per locale and an `x-default` pointing at the
 * default locale. Path is the segment after the locale (e.g. "" for the home
 * page, "pricing" for a sub-page).
 */
export function localeAlternates(locale: string, path = "") {
  const languages: Record<string, string> = {};
  for (const l of routing.locales) {
    languages[l] = localeUrl(l, path);
  }
  languages["x-default"] = localeUrl(routing.defaultLocale, path);

  return {
    canonical: localeUrl(locale, path),
    languages,
  };
}
