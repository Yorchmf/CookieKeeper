"use client";

import { useTranslations } from "next-intl";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import type { ComplianceReport } from "@/lib/api/scans";
import { deriveComparisonRows } from "@/lib/public-scan-view";
import { cn } from "@/lib/utils";

/**
 * The conversion pitch of the free scan: a two-column "Without Complyr / With Complyr" table. Rows the
 * scan actually surfaced come first (personalised from the compliance issue codes — see
 * [deriveComparisonRows]), followed by the always-on product-value rows. A clean scan still shows the
 * value props, so the table never renders empty. Every word is localized; the row keys drive the copy.
 */
export function ScanComparison({ report }: { report: ComplianceReport }) {
  const t = useTranslations("marketing.scan.comparison");
  const { issueKeys, staticKeys } = deriveComparisonRows(report.issues);
  const rowKeys = [...issueKeys, ...staticKeys];

  return (
    <Card aria-labelledby="scan-comparison-heading">
      <CardHeader>
        <h4 id="scan-comparison-heading" className="text-lg font-semibold text-pretty">
          {t("title")}
        </h4>
      </CardHeader>
      <CardContent>
        <div
          role="region"
          aria-label={t("title")}
          tabIndex={0}
          className="overflow-x-auto focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        >
          <table className="w-full border-collapse text-sm">
            <caption className="sr-only">{t("title")}</caption>
            <thead>
              <tr className="border-b border-border text-left align-bottom">
                <th scope="col" className="py-2 pr-4 font-medium text-muted-foreground">
                  {t("columns.issue")}
                </th>
                <th scope="col" className="py-2 pr-4 font-medium text-destructive">
                  {t("columns.without")}
                </th>
                <th scope="col" className="py-2 font-medium text-emerald-700 dark:text-emerald-400">
                  {t("columns.with")}
                </th>
              </tr>
            </thead>
            <tbody>
              {rowKeys.map((key, index) => {
                // The scan-derived rows lead; the divider marks where the always-on value props begin.
                const isFirstStaticRow =
                  issueKeys.length > 0 && index === issueKeys.length;
                return (
                  <tr
                    key={key}
                    className={cn(
                      "border-b border-border/50 align-top last:border-0",
                      isFirstStaticRow && "border-t-2 border-t-border",
                    )}
                  >
                    <th
                      scope="row"
                      className="py-3 pr-4 text-left font-medium text-balance"
                    >
                      {t(`rows.${key}.label`)}
                    </th>
                    <td className="py-3 pr-4 text-muted-foreground text-pretty">
                      {t(`rows.${key}.without`)}
                    </td>
                    <td className="py-3 text-pretty">{t(`rows.${key}.with`)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  );
}
