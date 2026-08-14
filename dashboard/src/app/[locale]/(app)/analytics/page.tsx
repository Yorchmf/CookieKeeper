import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";

import { AccountAnalyticsView } from "@/components/analytics/account-analytics-view";
import { AnalyticsSkeleton } from "@/components/analytics/analytics";

export default async function AccountAnalyticsPage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  // The view reads the `?range=` window from the URL via useSearchParams, which requires a Suspense
  // boundary. The Pro/Business gate is enforced server-side (403) and mirrored in the view itself, so
  // an ungated account lands here and sees the upgrade prompt rather than a broken page.
  return (
    <Suspense fallback={<AnalyticsSkeleton className="m-6" />}>
      <AccountAnalyticsView />
    </Suspense>
  );
}
