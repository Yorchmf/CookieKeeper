"use client";

import { useTranslations } from "next-intl";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import type {
  ComplianceReport as ComplianceReportData,
  ComplianceSeverity,
} from "@/lib/api/scans";
import { cn } from "@/lib/utils";

/** Score bands drive the dominant numeral's colour: healthy → attention → alarm. */
const SCORE_GOOD = 90;
const SCORE_FAIR = 70;

/** Semantic accent per severity — a dot the eye scans before reading the row. Decorative (aria-hidden). */
const SEVERITY_DOT: Record<ComplianceSeverity, string> = {
  critical: "bg-destructive",
  warning: "bg-amber-500",
  info: "bg-sky-500",
};

function scoreTone(score: number): string {
  if (score >= SCORE_GOOD) return "text-emerald-600 dark:text-emerald-400";
  if (score >= SCORE_FAIR) return "text-amber-600 dark:text-amber-400";
  return "text-destructive";
}

/**
 * The indicative compliance headline for a completed scan: one dominant score numeral carrying the
 * hierarchy, then a severity-ranked list of what pulled it down. Purely presentational — the score and
 * machine issue codes come from the backend (`ComplianceAnalyzer`); every word here is localized. The
 * disclaimer keeps the score framed as guidance, not a legal determination.
 */
export function ComplianceReport({ report }: { report: ComplianceReportData }) {
  const t = useTranslations("scans.compliance");

  return (
    <Card aria-labelledby="compliance-heading">
      <CardHeader>
        <h2
          id="compliance-heading"
          className="text-sm font-medium tracking-wide text-muted-foreground uppercase"
        >
          {t("title")}
        </h2>
        <div className="flex items-end gap-4">
          <span
            className={cn(
              "text-6xl leading-none font-semibold tracking-tight tabular-nums sm:text-7xl",
              scoreTone(report.score),
            )}
          >
            {report.score}
          </span>
          <span className="max-w-[16ch] pb-1 text-sm text-pretty text-muted-foreground">
            {t("scoreCaption")}
          </span>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {report.issues.length === 0 ? (
          <p className="text-sm font-medium text-emerald-700 dark:text-emerald-400">
            {t("clean")}
          </p>
        ) : (
          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">{t("issuesTitle")}</h3>
            <ul className="flex flex-col gap-2">
              {report.issues.map((issue) => (
                <li
                  key={issue.code}
                  className="flex items-start gap-2.5 border-b border-border/60 pb-2 last:border-0"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "mt-1.5 size-2.5 shrink-0 rounded-full",
                      SEVERITY_DOT[issue.severity],
                    )}
                  />
                  <div className="flex flex-col">
                    <span className="text-sm">
                      {t(`issues.${issue.code}`, { count: issue.count })}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {t(`severity.${issue.severity}`)}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="text-xs text-pretty text-muted-foreground">
          {t("disclaimer")}
        </p>
      </CardContent>
    </Card>
  );
}
