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

/**
 * Where "a real human will reply" actually lands. Env-overridable so a staging build can point at a test
 * inbox; `support@complyr.eu` is the production default. Single source of truth for every contact link
 * (FAQ, marketing footer, in-app sidebar) so the address is changed in exactly one place.
 */
export const SUPPORT_EMAIL =
  process.env.NEXT_PUBLIC_SUPPORT_EMAIL ?? "support@complyr.eu";

/** `mailto:` href for the support address. */
export const SUPPORT_MAILTO = `mailto:${SUPPORT_EMAIL}`;

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
