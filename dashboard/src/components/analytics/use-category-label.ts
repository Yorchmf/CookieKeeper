"use client";

import { useTranslations } from "next-intl";

/** The taxonomy keys we ship a translated label for; anything else falls back to the raw key. */
const KNOWN_CATEGORIES = new Set([
  "necessary",
  "preferences",
  "statistics",
  "marketing",
  "unclassified",
]);

/**
 * Resolve a cookie/consent category key to its localized label, shared by the opt-in bars and the cookie
 * donut so both name a category identically. Unknown keys (a future taxonomy addition, a stray signature)
 * pass through verbatim rather than rendering a missing-message error.
 */
export function useCategoryLabel(): (category: string) => string {
  const t = useTranslations("analytics.categories");
  return (category: string) => (KNOWN_CATEGORIES.has(category) ? t(category) : category);
}
