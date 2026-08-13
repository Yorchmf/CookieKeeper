import { setRequestLocale } from "next-intl/server";
import { Suspense } from "react";
import { ConfirmEmailChangePanel } from "@/components/auth/confirm-email-change-panel";

export default async function ConfirmEmailChangePage(props: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await props.params;
  setRequestLocale(locale);

  return (
    // Suspense boundary required by useSearchParams (?token=) during prerender.
    <Suspense>
      <ConfirmEmailChangePanel />
    </Suspense>
  );
}
