"use client";

import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import type { ConsentAction, ConsentEvent } from "@/lib/api/consent";

/** Canonical consent-category order (essential first), mirroring the widget's category stack. */
const CATEGORY_ORDER = ["necessary", "preferences", "statistics", "marketing"] as const;
const KNOWN_CATEGORIES = new Set<string>(CATEGORY_ORDER);

/** Granted categories in canonical order, with any unknown keys appended alphabetically. */
function grantedCategories(categories: Record<string, boolean>): string[] {
  const granted = Object.keys(categories).filter((key) => categories[key]);
  const ordered = CATEGORY_ORDER.filter((category) => granted.includes(category));
  const extra = granted.filter((category) => !KNOWN_CATEGORIES.has(category)).sort();
  return [...ordered, ...extra];
}

function actionBadgeVariant(action: ConsentAction): "default" | "outline" | "secondary" {
  if (action === "accept_all") return "default";
  if (action === "reject_all") return "outline";
  return "secondary";
}

/**
 * Accessible, newest-first table of consent events. Purely presentational — paging lives upstream.
 * The i18n/format hooks are read once here (not per row) so an infinitely growing list does not add a
 * next-intl subscription per rendered event.
 */
export function ConsentEventsTable({
  events,
  caption,
}: {
  events: ConsentEvent[];
  caption: string;
}) {
  const t = useTranslations("consentLog");
  const tColumns = useTranslations("consentLog.columns");
  const tCategories = useTranslations("scans.categories");
  const format = useFormatter();
  const unknownValue = t("unknownValue");

  return (
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
              {tColumns("when")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {tColumns("visitor")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {tColumns("action")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {tColumns("categories")}
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              {tColumns("language")}
            </th>
            <th scope="col" className="py-2 font-medium">
              {tColumns("version")}
            </th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => {
            const granted = grantedCategories(event.categories);
            return (
              <tr
                key={event.eventId}
                className="border-b border-border/60 align-top last:border-0"
              >
                <td className="py-2 pr-4 whitespace-nowrap text-muted-foreground">
                  {format.dateTime(new Date(event.createdAt), {
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </td>
                <th
                  scope="row"
                  className="py-2 pr-4 text-left font-mono text-xs font-normal"
                >
                  {event.visitorId.slice(0, 8)}
                </th>
                <td className="py-2 pr-4">
                  <Badge variant={actionBadgeVariant(event.action)}>
                    {t(`actions.${event.action}`)}
                  </Badge>
                </td>
                <td className="py-2 pr-4">
                  {granted.length === 0 ? (
                    <span className="text-muted-foreground">{t("categoriesNone")}</span>
                  ) : (
                    <span className="flex flex-wrap gap-1">
                      {granted.map((category) => (
                        <Badge key={category} variant="secondary" className="font-normal">
                          {tCategories.has(category) ? tCategories(category) : category}
                        </Badge>
                      ))}
                    </span>
                  )}
                </td>
                <td className="py-2 pr-4 text-muted-foreground uppercase">
                  {event.lang ?? unknownValue}
                </td>
                <td className="py-2 tabular-nums text-muted-foreground">
                  {event.bannerVersion ?? unknownValue} / {event.policyVersion ?? unknownValue}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
