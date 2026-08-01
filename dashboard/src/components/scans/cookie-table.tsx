"use client";

import { useTranslations } from "next-intl";
import type { ScanCookie } from "@/lib/api/scans";

/**
 * Canonical consent categories in the order the widget stacks them (essential first, marketing last).
 * Any unexpected key from the backend is appended after these so a new category never silently drops.
 */
export const CATEGORY_ORDER = [
  "necessary",
  "preferences",
  "statistics",
  "marketing",
] as const;

const KNOWN_CATEGORIES = new Set<string>(CATEGORY_ORDER);

/** Known categories first (widget stack order), then any unexpected keys sorted alphabetically. */
export function orderedCategories(present: string[]): string[] {
  const known = CATEGORY_ORDER.filter((c) => present.includes(c));
  const extra = present.filter((c) => !KNOWN_CATEGORIES.has(c));
  return [...known, ...extra.sort()];
}

/**
 * Accessible four-column cookie table shared by the owned-scan results view and the public
 * marketing report. Reads shared column/label copy from the `scans` message namespace.
 */
export function CookieTable({
  cookies,
  caption,
}: {
  cookies: ScanCookie[];
  caption: string;
}) {
  const t = useTranslations("scans");
  return (
    // Focusable + labelled so keyboard-only users can scroll to columns clipped on narrow viewports.
    <div
      role="region"
      aria-label={caption}
      tabIndex={0}
      className="overflow-x-auto focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-border text-left text-muted-foreground">
            <th scope="col" className="py-2 pr-4 font-medium">
              {t("results.columns.name")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t("results.columns.provider")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {t("results.columns.domain")}
            </th>
            <th scope="col" className="py-2 font-medium">
              {t("results.columns.expiry")}
            </th>
          </tr>
        </thead>
        <tbody>
          {cookies.map((cookie) => (
            <tr
              key={[cookie.name, cookie.domain ?? "", cookie.expiry ?? ""].join(
                " ",
              )}
              className="border-b border-border/50 last:border-0"
            >
              <th
                scope="row"
                className="py-2 pr-4 text-left font-mono font-normal"
              >
                {cookie.name}
              </th>
              <td className="py-2 pr-4">
                {cookie.provider ?? (
                  <span className="text-muted-foreground">
                    {t("results.unknownValue")}
                  </span>
                )}
              </td>
              <td className="py-2 pr-4 text-muted-foreground">
                {cookie.domain ?? t("results.unknownValue")}
              </td>
              <td className="py-2 text-muted-foreground">
                {cookie.expiry ?? t("results.unknownValue")}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
