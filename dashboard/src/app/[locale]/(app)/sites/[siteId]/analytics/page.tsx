import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";

import { Analytics, AnalyticsSkeleton } from "@/components/analytics/analytics";

export default async function AnalyticsPage(props: {
  params: Promise<{ locale: string; siteId: string }>;
}) {
  const { locale, siteId } = await props.params;
  setRequestLocale(locale);

  // Analytics reads the `?range=` window from the URL via useSearchParams, which requires a Suspense
  // boundary. Render throws are caught by the segment error boundary in ./error.tsx.
  return (
    <Suspense fallback={<AnalyticsSkeleton className="m-6" />}>
      <Analytics siteId={siteId} />
    </Suspense>
  );
}
