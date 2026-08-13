"use client";

import { useFormatter, useTranslations } from "next-intl";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { ACTION_COLORS, CHART_SURFACE } from "@/components/analytics/chart-theme";
import type { ConsentTrendPoint } from "@/lib/api/analytics";

/**
 * Stacked-area consent trend over the window: accept / reject / custom composed per UTC day, so the band
 * height reads as total volume while the split shows the decision mix. Areas are the semantic action
 * colours (see chart-theme). Empty windows are handled by the caller, not here.
 */
export function ConsentTrendChart({ trend }: { trend: ConsentTrendPoint[] }) {
  const t = useTranslations("analytics");
  const format = useFormatter();

  // "2026-08-05" → a locale short day/month label. Appending T00:00:00Z pins it to the UTC day the
  // backend bucketed on, so the axis never drifts by a day in western-of-UTC timezones.
  const formatDay = (value: string) =>
    format.dateTime(new Date(`${value}T00:00:00Z`), { day: "numeric", month: "short" });

  return (
    <div>
      {/* Full figures for screen readers — the chart below conveys the same data visually but collapses
          its SVG subtree behind the summary aria-label, so the actual per-day numbers need a text escape hatch. */}
      <table className="sr-only">
        <caption>{t("consentTrend.ariaLabel")}</caption>
        <thead>
          <tr>
            <th scope="col">{t("consentTrend.dateColumn")}</th>
            <th scope="col">{t("actions.accept_all")}</th>
            <th scope="col">{t("actions.reject_all")}</th>
            <th scope="col">{t("actions.custom")}</th>
          </tr>
        </thead>
        <tbody>
          {trend.map((point) => (
            <tr key={point.date}>
              <th scope="row">{formatDay(point.date)}</th>
              <td>{point.acceptAll}</td>
              <td>{point.rejectAll}</td>
              <td>{point.custom}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div role="img" aria-label={t("consentTrend.ariaLabel")}>
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart
            data={trend}
            margin={{ top: 8, right: 8, bottom: 0, left: -12 }}
            accessibilityLayer={false}
          >
            <CartesianGrid stroke={CHART_SURFACE.grid} strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="date"
              tickFormatter={formatDay}
              tick={{ fontSize: 11, fill: CHART_SURFACE.axis }}
              tickLine={false}
              axisLine={{ stroke: CHART_SURFACE.grid }}
              minTickGap={24}
            />
            <YAxis
              allowDecimals={false}
              width={40}
              tick={{ fontSize: 11, fill: CHART_SURFACE.axis }}
              tickLine={false}
              axisLine={false}
            />
            <Tooltip
              labelFormatter={(value) => formatDay(String(value))}
              contentStyle={{
                backgroundColor: CHART_SURFACE.tooltipBg,
                border: `1px solid ${CHART_SURFACE.tooltipBorder}`,
                borderRadius: 12,
                color: CHART_SURFACE.tooltipText,
                fontSize: 12,
              }}
            />
            <Area
              type="monotone"
              dataKey="acceptAll"
              name={t("actions.accept_all")}
              stackId="consent"
              stroke={ACTION_COLORS.acceptAll}
              strokeWidth={2}
              fill={ACTION_COLORS.acceptAll}
              fillOpacity={0.55}
            />
            <Area
              type="monotone"
              dataKey="rejectAll"
              name={t("actions.reject_all")}
              stackId="consent"
              stroke={ACTION_COLORS.rejectAll}
              strokeWidth={2}
              fill={ACTION_COLORS.rejectAll}
              fillOpacity={0.5}
            />
            <Area
              type="monotone"
              dataKey="custom"
              name={t("actions.custom")}
              stackId="consent"
              stroke={ACTION_COLORS.custom}
              strokeWidth={2}
              fill={ACTION_COLORS.custom}
              fillOpacity={0.45}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
