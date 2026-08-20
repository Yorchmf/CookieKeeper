"use client";

import { useFormatter, useTranslations } from "next-intl";

import { useCategoryLabel } from "@/components/analytics/use-category-label";
import type { ConsentRepromptNotice } from "@/lib/api/analytics";

/**
 * Explains a consent re-prompt that happened inside the displayed window (BACKLOG #18).
 *
 * Sits directly above the summary tiles because that is what it qualifies: when the site starts using a
 * category its stored consents never covered, the widget asks visitors again, so the banner is re-shown to
 * people who already had a valid choice. Impressions jump and the interaction rate steps — a discontinuity
 * the customer would otherwise read as a change in their own traffic. Saying so at the point where the step
 * appears is the honest version; a silent step is a support ticket.
 *
 * Informational, not a warning: the re-prompt is the compliant outcome, and the copy says why it happened.
 */
export function RepromptNotice({ notice }: { notice: ConsentRepromptNotice }) {
  const t = useTranslations("analytics.reprompt");
  const format = useFormatter();
  const categoryLabel = useCategoryLabel();

  const categories = notice.addedCategories.map(categoryLabel);

  return (
    <aside
      aria-labelledby="analytics-reprompt-heading"
      className="rounded-xl border border-primary/25 bg-primary/5 p-4"
    >
      <h2 id="analytics-reprompt-heading" className="text-sm font-semibold">
        {categories.length > 0
          ? t("titleWithCategories", {
              categories: format.list(categories, { type: "conjunction" }),
            })
          : t("title")}
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t("body", {
          when: format.dateTime(new Date(notice.changedAt), { dateStyle: "medium" }),
        })}
      </p>
      <p className="mt-2 text-sm text-muted-foreground">{t("caveat")}</p>
    </aside>
  );
}
