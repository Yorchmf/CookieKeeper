"use client";

import { useFormatter, useTranslations } from "next-intl";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";

import { categoryColor, CHART_SURFACE } from "@/components/analytics/chart-theme";
import { StatTile } from "@/components/analytics/stat-tile";
import { useCategoryLabel } from "@/components/analytics/use-category-label";
import type { CookieAnalytics } from "@/lib/api/analytics";

/**
 * Cookie inventory from the site's most recent completed scan: a category donut beside the headline
 * counts (total / known / unknown) and the two compliance-risk figures (insecure cookies, marketing
 * trackers) rendered in the warning tone. The scan timestamp is shown so the reader knows how fresh
 * this snapshot is — unlike the consent figures, it is point-in-time, not windowed.
 */
export function CookieInventory({ cookies }: { cookies: CookieAnalytics }) {
  const t = useTranslations("analytics");
  const format = useFormatter();
  const labelFor = useCategoryLabel();

  const slices = cookies.byCategory.map((entry) => ({
    ...entry,
    label: labelFor(entry.category),
  }));

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatTile label={t("cookies.total")} value={cookies.total} />
        <StatTile label={t("cookies.known")} value={cookies.known} />
        <StatTile label={t("cookies.unknown")} value={cookies.unknown} />
        <StatTile
          label={t("cookies.insecure")}
          value={cookies.insecure}
          tone={cookies.insecure > 0 ? "warning" : "default"}
          warningLabel={t("warningLabel")}
        />
        <StatTile
          label={t("cookies.trackers")}
          value={cookies.trackerCount}
          tone={cookies.trackerCount > 0 ? "warning" : "default"}
          warningLabel={t("warningLabel")}
        />
      </div>

      {slices.length > 0 ? (
        <div
          className="flex flex-col items-center gap-4 sm:flex-row"
          role="img"
          aria-label={t("cookies.ariaLabel")}
        >
          <ResponsiveContainer width="100%" height={180} className="max-w-[220px]">
            <PieChart accessibilityLayer={false}>
              <Pie
                data={slices}
                dataKey="count"
                nameKey="label"
                innerRadius={45}
                outerRadius={80}
                paddingAngle={2}
                stroke="var(--card)"
                isAnimationActive={false}
              >
                {slices.map((slice, index) => (
                  <Cell key={slice.category} fill={categoryColor(index)} />
                ))}
              </Pie>
              <Tooltip
                contentStyle={{
                  backgroundColor: CHART_SURFACE.tooltipBg,
                  border: `1px solid ${CHART_SURFACE.tooltipBorder}`,
                  borderRadius: 12,
                  color: CHART_SURFACE.tooltipText,
                  fontSize: 12,
                }}
              />
            </PieChart>
          </ResponsiveContainer>

          <ul className="flex flex-1 flex-col gap-1.5">
            {slices.map((slice, index) => (
              <li key={slice.category} className="flex items-center gap-2 text-sm">
                <span
                  aria-hidden="true"
                  className="size-3 shrink-0 rounded-sm"
                  style={{ backgroundColor: categoryColor(index) }}
                />
                <span className="flex-1">{slice.label}</span>
                <span className="tabular-nums text-muted-foreground">{slice.count}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <p className="text-xs text-muted-foreground">
        {t("cookies.scannedAt", {
          when: format.dateTime(new Date(cookies.scannedAt), {
            dateStyle: "medium",
            timeStyle: "short",
          }),
        })}
      </p>
    </div>
  );
}
