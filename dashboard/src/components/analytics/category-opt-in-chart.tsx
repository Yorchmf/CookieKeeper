"use client";

import { useTranslations } from "next-intl";
import {
  Bar,
  BarChart,
  Cell,
  LabelList,
  ResponsiveContainer,
  XAxis,
  YAxis,
} from "recharts";

import { CHART_SURFACE, RATE_COLOR } from "@/components/analytics/chart-theme";
import { useCategoryLabel } from "@/components/analytics/use-category-label";
import type { CategoryOptIn } from "@/lib/api/analytics";

/**
 * Horizontal opt-in-rate bars, one per consent category observed in the window. The bar length is the
 * share of decisions that opted the category in (0–100%); the raw `optIns/decisions` count rides along
 * as the row's accessible name so the percentage is never read without its denominator.
 */
export function CategoryOptInChart({ categories }: { categories: CategoryOptIn[] }) {
  const t = useTranslations("analytics");
  const labelFor = useCategoryLabel();

  const rows = categories.map((entry) => ({
    ...entry,
    label: labelFor(entry.category),
    ratePct: Math.round(entry.rate * 100),
  }));

  return (
    <div>
      {/* Full figures for screen readers — the chart below conveys the same data visually but collapses
          its SVG subtree behind the summary aria-label, so the actual counts need a text escape hatch. */}
      <table className="sr-only">
        <caption>{t("categoryOptIn.ariaLabel")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("categoryOptIn.categoryColumn")}</th>
            <th scope="col">{t("categoryOptIn.rateColumn")}</th>
            <th scope="col">{t("categoryOptIn.decisionsColumn")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.category}>
              <th scope="row">{row.label}</th>
              <td>{row.ratePct}%</td>
              <td>{`${row.optIns}/${row.decisions}`}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div role="img" aria-label={t("categoryOptIn.ariaLabel")}>
        <ResponsiveContainer width="100%" height={Math.max(140, rows.length * 44)}>
          <BarChart
            data={rows}
            layout="vertical"
            margin={{ top: 4, right: 40, bottom: 4, left: 8 }}
            accessibilityLayer={false}
          >
            <XAxis type="number" domain={[0, 100]} hide />
            <YAxis
              type="category"
              dataKey="label"
              width={96}
              tick={{ fontSize: 12, fill: CHART_SURFACE.axis }}
              tickLine={false}
              axisLine={false}
            />
            <Bar dataKey="ratePct" radius={[4, 4, 4, 4]} barSize={16} isAnimationActive={false}>
              {rows.map((row) => (
                <Cell key={row.category} fill={RATE_COLOR} />
              ))}
              <LabelList
                dataKey="ratePct"
                position="right"
                formatter={(value) => `${value ?? 0}%`}
                style={{ fontSize: 11, fill: CHART_SURFACE.axis }}
              />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
