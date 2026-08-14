"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";

/**
 * Segment error boundary for the cross-site analytics dashboard. Data-fetch failures are handled inline
 * by the query (isError), so this catches render-time throws — the Error Boundary half of the Suspense pair.
 */
export default function AccountAnalyticsError({ reset }: { error: Error; reset: () => void }) {
  const t = useTranslations("analytics");

  return (
    <main className="flex-1 p-6">
      <div role="alert" className="flex max-w-md flex-col items-start gap-3">
        <p className="text-sm text-destructive">{t("crossSite.loadError")}</p>
        <Button variant="outline" onClick={reset}>
          {t("retry")}
        </Button>
      </div>
    </main>
  );
}
