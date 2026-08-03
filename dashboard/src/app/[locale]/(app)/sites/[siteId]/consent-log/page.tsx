import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";

import { ConsentLog, ConsentLogSkeleton } from "@/components/consent-log/consent-log";

export default async function ConsentLogPage(props: {
  params: Promise<{ locale: string; siteId: string }>;
}) {
  const { locale, siteId } = await props.params;
  setRequestLocale(locale);

  // ConsentLog reads filters from the URL via useSearchParams, which requires a Suspense boundary.
  // Render throws are caught by the segment error boundary in ./error.tsx.
  return (
    <Suspense fallback={<ConsentLogSkeleton className="m-6" />}>
      <ConsentLog siteId={siteId} />
    </Suspense>
  );
}
